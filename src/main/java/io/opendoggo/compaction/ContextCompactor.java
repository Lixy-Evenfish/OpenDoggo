package io.opendoggo.compaction;

import io.opendoggo.model.Message;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * s08 上下文压缩管线（references/code.py 的 ContextCompactor：
 * 需求1 的确定性整理 + 需求2 的历史摘要与响应式补救）。
 *
 * prepare 在每轮模型调用前执行，前三步可恢复、零模型调用：
 *
 *   1. toolResultBudget —— 最新一批 tool_result 总量超过
 *      200,000 字符时，从最大的结果开始，把超过 30,000 字符的
 *      完整落盘，上下文里只留路径 + 前 2,000 字符预览；
 *   2. snipCompact —— 消息数超过 50 条时保留头 3 条 + 尾 46 条，
 *      中段完整写入 .transcripts/，原位换成归档标记消息，
 *      切点保护 tool_use / tool_result 配对；
 *   3. microCompact / fitToolResults —— 仅当 estimateChars 仍超过
 *      50,000 时执行（目标 40,000，即 80%）：模型已读过的旧结果
 *      保留最近 3 条、更早的替换为可恢复路径引用；未读结果本身
 *      超限时换成 1,000 字符预览；
 *   4. compactHistory —— 仍超限时的有损兜底：留全量 transcript，
 *      花一次模型调用生成事实摘要，历史替换为一条 [Compacted]
 *      消息——Current user request 与 JSON 转义的摘要明确分区
 *      （防注入）。reactiveCompact 处理 API 的 prompt_too_long
 *      拒绝：保留最近 5 条消息，更早历史换 [Reactive compact]
 *      摘要消息，只重试一次。
 *
 * 前三步的改动都在 .task_outputs/tool-results/ 或 .transcripts/
 * 留下恢复路径——确定性、可找回；只有第四步引入模型调用。
 * 估算单位是字符：estimateChars 把 messages 序列化成与请求体
 * 同形的 JSON 后取长度（对应参考实现的
 * len(json.dumps(..., ensure_ascii=False))）。
 */
public final class ContextCompactor {

    public static final int CONTEXT_CHAR_LIMIT = 50_000;

    public static final int TOOL_RESULT_BATCH_CHAR_LIMIT = 200_000;

    public static final int LARGE_RESULT_CHAR_LIMIT = 30_000;

    public static final int KEEP_RECENT_RESULTS = 3;

    // 需求2：摘要输入上限与响应式补救的保留条数。
    public static final int SUMMARY_INPUT_CHAR_LIMIT = 80_000;

    public static final int KEEP_RECENT_MESSAGES = 5;

    // snip：消息数上限（保留头 3 + 标记 1 + 尾 46）。
    private static final int MAX_MESSAGES = 50;

    private static final int SNIP_HEAD_MESSAGES = 3;

    // micro：已读结果换引用的最小长度（更短的不值得替换）。
    private static final int MICRO_SHORT_RESULT_LIMIT = 120;

    private static final double MICRO_TARGET_RATIO = 0.8;

    // 预算步的预览 2,000 字符；fit 步的预览 1,000 字符。
    private static final int BUDGET_PREVIEW_CHARS = 2000;

    private static final int FIT_PREVIEW_CHARS = 1000;

    // 落盘文件名里的 tool_use_id 清洗上限。
    private static final int SAFE_ID_LIMIT = 120;

    private static final String PERSISTED_OPEN =
            "<persisted-output>\n";

    private static final String FULL_OUTPUT_PREFIX =
            "Full output: ";

    private static final String SAVED_AT_PREFIX =
            "[Earlier tool result saved at ";

    private static final String SAVED_AT_SUFFIX = "]";

    private static final Pattern ARCHIVE_MARKER =
            Pattern.compile("\\[(\\d+) messages archived at (.+)]");

    private static final Pattern UNSAFE_ID_CHARS =
            Pattern.compile("[^A-Za-z0-9._-]");

    private static final ObjectMapper MAPPER =
            new ObjectMapper();

    private final Path transcriptDir;

    private final Path toolResultsDir;

    // 需求2：历史摘要的调用方（生产实现是摘要专用 client，
    // demo / 测试用 lambda 脚本）。
    private final HistorySummarizer summarizer;

    public ContextCompactor(
            Path transcriptDir,
            Path toolResultsDir,
            HistorySummarizer summarizer
    ) {
        this.transcriptDir = Objects.requireNonNull(
                transcriptDir,
                "transcriptDir cannot be null"
        );

        this.toolResultsDir = Objects.requireNonNull(
                toolResultsDir,
                "toolResultsDir cannot be null"
        );

        this.summarizer = Objects.requireNonNull(
                summarizer,
                "summarizer cannot be null"
        );
    }

    /**
     * 管线入口：每轮模型调用前执行。
     *
     * 前两步永远执行；第三、四步只在估算仍超限时执行。
     * activeRequest 是本轮的用户请求原文——压缩可能替换掉
     * 承载它的那条消息，所以它必须作为参数随行，
     * 第四步把它写进 [Compacted] 消息的 Current user request。
     */
    public void prepare(
            List<Message> messages,
            String activeRequest
    ) {
        toolResultBudget(messages);
        snipCompact(messages);

        if (estimateChars(messages) > CONTEXT_CHAR_LIMIT) {
            int target =
                    (int) (CONTEXT_CHAR_LIMIT * MICRO_TARGET_RATIO);

            microCompact(messages, target);

            if (estimateChars(messages) > CONTEXT_CHAR_LIMIT) {
                fitToolResults(messages, target);
            }

            if (estimateChars(messages) > CONTEXT_CHAR_LIMIT) {
                compactHistory(messages, activeRequest);
            }
        }
    }

    /**
     * 第一步：只处理最后一条消息——
     * 必须是 user + 数组内容（刚执行完的工具结果批次）。
     * 总量回到限内即停，因此只动最大的那几个结果；
     * 不超过 30,000 字符的结果这一步永远不碰
     * （多个小结果撑爆的情况留给后面几步）。
     *
     * 流程：筛出最新批次的工具结果 -> 算总量 ->
     * 按大小降序逐个尝试 -> 总量回到限内就停 ->
     * 只对超 30k 的大结果"落盘 + 换预览块"。
     * 改的是共享的 JSON 树节点，方法返回后
     * AgentLoop / Main 手里的 messages 已经是
     * 压缩后的状态，不需要返回值回传。
     */
    public void toolResultBudget(List<Message> messages) {
        if (messages.isEmpty()) {
            return;
        }

        Message last =
                messages.get(messages.size() - 1);

        if (!"user".equals(last.role())
                || !last.content().isArray()) {
            return;
        }

        List<ObjectNode> blocks =
                toolResultBlocks(last.content());

        int total = sumContents(blocks);

        List<ObjectNode> ranked = new ArrayList<>(blocks);
        ranked.sort(Comparator.comparingInt(
                        ContextCompactor::contentLength)
                .reversed());

        for (ObjectNode block : ranked) {
            if (total <= TOOL_RESULT_BATCH_CHAR_LIMIT) {
                break;
            }

            String output = blockContent(block);

            if (output.length() <= LARGE_RESULT_CHAR_LIMIT) {
                continue;
            }

            // 走到这里的一定是 > 30k 的大结果
            // （小结果已被上面的 continue 过滤）——
            // 直接生成 2,000 字符预览的落盘块。
            block.put(
                    "content",
                    persistedPreview(
                            toolUseId(block),
                            output,
                            BUDGET_PREVIEW_CHARS
                    )
            );

            total = sumContents(blocks);
        }
    }

    /**
     * 第二步：消息数超过 50 条时归档中段。
     *
     * 头切点若落在 tool_use 之后，向后吞掉紧随的
     * tool_result 消息；尾切点若切在配对中间则前移一位——
     * 孤立的 tool_result 会让下一次 API 请求被判无效。
     * 中段只剩一条旧标记时不再写新 transcript（幂等）。
     */
    public void snipCompact(List<Message> messages) {
        if (messages.size() <= MAX_MESSAGES) {
            return;
        }

        int headEnd = SNIP_HEAD_MESSAGES;
        int tailStart =
                messages.size()
                        - (MAX_MESSAGES
                        - SNIP_HEAD_MESSAGES
                        - 1);

        if (hasToolUse(messages.get(headEnd - 1))) {
            while (headEnd < tailStart
                    && isToolResult(
                    messages.get(headEnd))) {
                headEnd++;
            }
        }

        if (tailStart > 0
                && isToolResult(messages.get(tailStart))
                && hasToolUse(
                messages.get(tailStart - 1))) {
            tailStart--;
        }

        if (headEnd >= tailStart) {
            return;
        }

        List<Message> middle =
                messages.subList(headEnd, tailStart);

        if (middle.size() == 1
                && isArchiveMarker(middle.get(0))) {
            return;
        }

        Path transcript = writeTranscript(messages);
        Message marker = Message.user(
                "["
                        + (tailStart - headEnd)
                        + " messages archived at "
                        + transcript
                        + "]"
        );

        List<Message> kept =
                new ArrayList<>(
                        messages.subList(0, headEnd)
                );

        kept.add(marker);
        kept.addAll(
                messages.subList(
                        tailStart,
                        messages.size()
                )
        );

        // 原地重写同一个 List——Main.runRepl 持有的
        // 引用（以及失败回滚的 checkpoint 语义）保持不变。
        messages.clear();
        messages.addAll(kept);
    }

    /**
     * 第三步 a：把模型已读过的旧 tool_result 换成路径引用。
     *
     * 未读结果（最后一条 assistant 消息之后的新批次）不动；
     * 已读结果保留最近 3 条，更早且超过 120 字符的替换为
     * "[Earlier tool result saved at ...]"。targetChars 非 null 时
     * 估算一旦回到目标内即停。
     */
    public void microCompact(
            List<Message> messages,
            Integer targetChars
    ) {
        List<PositionedBlock> results =
                collectToolResults(messages);

        Set<BlockPosition> unseen =
                unseenToolResultPositions(messages);

        List<PositionedBlock> consumed = new ArrayList<>();

        for (PositionedBlock entry : results) {
            if (!unseen.contains(entry.position())) {
                consumed.add(entry);
            }
        }

        int candidates = Math.max(
                0,
                consumed.size() - KEEP_RECENT_RESULTS
        );

        for (int index = 0; index < candidates; index++) {

            ObjectNode block =
                    consumed.get(index).block();

            if (targetChars != null
                    && estimateChars(messages)
                    <= targetChars) {
                break;
            }

            String content = blockContent(block);

            if (content.length()
                    <= MICRO_SHORT_RESULT_LIMIT) {
                continue;
            }

            String savedPath =
                    persistedOutputPath(content);

            if (savedPath == null) {
                savedPath = saveOutput(
                        toolUseId(block),
                        content
                ).toString();
            }

            block.put(
                    "content",
                    SAVED_AT_PREFIX
                            + savedPath
                            + SAVED_AT_SUFFIX
            );
        }
    }

    /**
     * 第三步 b：未读新结果本身就把上下文撑爆时的逃生通道。
     *
     * 所有 tool_result（读没读过都算）按大小降序，
     * 换成 1,000 字符预览块——模型至少能看到预览和恢复路径，
     * 而不是在读到新结果之前就被迫总结整段历史。
     * 替换只在真的更短时发生。
     */
    public void fitToolResults(
            List<Message> messages,
            int targetChars
    ) {
        List<ObjectNode> results = new ArrayList<>();

        for (Message message : messages) {
            if ("user".equals(message.role())
                    && message.content().isArray()) {
                results.addAll(
                        toolResultBlocks(
                                message.content()
                        )
                );
            }
        }

        results.sort(Comparator.comparingInt(
                        ContextCompactor::contentLength)
                .reversed());

        for (ObjectNode block : results) {
            if (estimateChars(messages) <= targetChars) {
                break;
            }

            String output = blockContent(block);
            String replacement = persistedPreview(
                    toolUseId(block),
                    output,
                    FIT_PREVIEW_CHARS
            );

            if (replacement.length()
                    < output.length()) {
                block.put("content", replacement);
            }
        }
    }

    /**
     * 第四步（需求2）：确定性整理仍超限时的有损兜底。
     *
     * 留全量 transcript，花一次模型调用生成事实摘要，
     * 整段历史原地替换为一条 [Compacted] 消息。
     * 当前用户请求（activeRequest）与摘要明确分区，
     * 摘要做 JSON 转义——配合 SYSTEM 的防注入句，
     * 历史里的旧指令无法借摘要冒充当前请求。
     */
    public void compactHistory(
            List<Message> messages,
            String activeRequest
    ) {
        Path transcript = writeTranscript(messages);

        String summary = summarizeHistory(messages);
        Message message = summaryMessage(
                "Compacted",
                activeRequest,
                summary,
                transcript
        );

        messages.clear();
        messages.add(message);
    }

    /**
     * API 拒绝（prompt_too_long / too many tokens）后的补救：
     * 留全量 transcript，保留最近 5 条消息（切点带配对保护；
     * tailStart == 0 时整段历史进摘要），更早历史换成一条
     * [Reactive compact] 摘要消息。是否重试由 AgentLoop 的
     * 计数器控制（最多一次）。
     */
    public void reactiveCompact(
            List<Message> messages,
            String activeRequest
    ) {
        Path transcript = writeTranscript(messages);

        int tailStart = Math.max(
                0,
                messages.size() - KEEP_RECENT_MESSAGES
        );

        if (tailStart > 0
                && isToolResult(messages.get(tailStart))
                && hasToolUse(
                messages.get(tailStart - 1))) {
            tailStart--;
        }

        List<Message> oldHistory = tailStart > 0
                ? new ArrayList<>(
                messages.subList(0, tailStart)
        )
                : new ArrayList<>(messages);

        String summary = summarizeHistory(oldHistory);
        Message message = summaryMessage(
                "Reactive compact",
                activeRequest,
                summary,
                transcript
        );

        List<Message> kept = new ArrayList<>();
        kept.add(message);

        if (tailStart > 0) {
            kept.addAll(
                    messages.subList(
                            tailStart,
                            messages.size()
                    )
            );
        }

        messages.clear();
        messages.addAll(kept);
    }

    /**
     * 一次真实的模型调用：输入是整段对话 JSON，输出是
     * 只含事实的状态摘要；空结果退化为 "(empty summary)"。
     * 失败向上传播——摘要失败应当中止本轮，
     * 而不是悄悄跳过压缩。
     */
    private String summarizeHistory(List<Message> messages) {
        String summary;

        try {
            summary = summarizer.summarize(
                    summaryInput(messages)
            );

        } catch (IOException exception) {
            throw new UncheckedIOException(exception);

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Summarization interrupted",
                    exception
            );
        }

        if (summary == null || summary.isBlank()) {
            return "(empty summary)";
        }

        return summary.strip();
    }

    /**
     * 摘要输入：整段对话 JSON；超过 80,000 字符时保留
     * 头 20,000 + 尾 60,000，中段以省略标记拼接
     * （全文在磁盘 transcript 里）。
     */
    private String summaryInput(List<Message> messages) {
        String conversation =
                serializeMessages(messages);

        if (conversation.length()
                <= SUMMARY_INPUT_CHAR_LIMIT) {
            return conversation;
        }

        int head = SUMMARY_INPUT_CHAR_LIMIT / 4;
        int tail = SUMMARY_INPUT_CHAR_LIMIT - head;

        return conversation.substring(0, head)
                + "\n...[middle omitted; full transcript "
                + "is on disk]...\n"
                + conversation.substring(
                conversation.length() - tail
        );
    }

    /**
     * 压缩后的单条 user 消息。label 区分自动（Compacted）
     * 与响应式（Reactive compact）；摘要做 JSON 转义
     * （带引号的字符串字面量），防止历史内容借摘要
     * 伪造消息结构。
     */
    private static Message summaryMessage(
            String label,
            String request,
            String summary,
            Path transcript
    ) {
        return Message.user(
                "[" + label + "]\n\n"
                        + "Current user request:\n"
                        + request
                        + "\n\nConversation summary "
                        + "(reference only):\n"
                        + jsonEscape(summary)
                        + "\n\nFull transcript: "
                        + transcript
        );
    }

    private static String jsonEscape(String value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return "\"" + value + "\"";
        }
    }

    /**
     * 上下文估算：序列化成与请求体同形的 JSON 取字符数。
     */
    public int estimateChars(List<Message> messages) {
        return serializeMessages(messages).length();
    }

    /**
     * 整段历史的紧凑 JSON 文本——estimateChars 取长度，
     * summaryInput 直接作为摘要输入。
     */
    private static String serializeMessages(
            List<Message> messages
    ) {
        ArrayNode array = MAPPER.createArrayNode();

        for (Message message : messages) {
            ObjectNode node = array.addObject();
            node.put("role", message.role());
            node.set("content", message.content());
        }

        return array.toString();
    }

    /**
     * 防重复落盘的关键：识别两种已有占位
     * 并返回可信路径——persisted-output 预览块里的
     * "Full output: ..." 行，或
     * "[Earlier tool result saved at ...]" 引用。
     * 路径必须仍在 tool-results 目录内且文件存在，
     * 否则返回 null（调用方会重新落盘，
     * 不会信上下文里的外部路径——占位里的路径
     * 也可能被模型生成的内容伪造）。
     */
    public String persistedOutputPath(String output) {
        String candidate = null;

        if (output.startsWith(PERSISTED_OPEN)) {
            for (String line
                    : output.split("\n", -1)) {
                if (line.startsWith(
                        FULL_OUTPUT_PREFIX)) {
                    candidate = line.substring(
                            FULL_OUTPUT_PREFIX
                                    .length()
                    );
                    break;
                }
            }
        }

        if (output.startsWith(SAVED_AT_PREFIX)
                && output.endsWith(SAVED_AT_SUFFIX)) {
            candidate = output.substring(
                    SAVED_AT_PREFIX.length(),
                    output.length()
                            - SAVED_AT_SUFFIX.length()
            );
        }

        if (candidate == null) {
            return null;
        }

        Path path = Path.of(candidate);

        if (!isInside(path, toolResultsDir)
                || !Files.isRegularFile(path)) {
            return null;
        }

        return path.toString();
    }

    /**
     * 结果全文落盘（覆盖写）。
     * 文件名 = 清洗后的 tool_use_id：非法字符换成 "_"、
     * 截断到 120 字符、清洗后为空则用 "unknown"，
     * 后缀 .txt，落在 tool-results 目录下。
     */
    public Path saveOutput(String toolUseId, String output) {
        try {
            Files.createDirectories(toolResultsDir);

            String safeId = UNSAFE_ID_CHARS
                    .matcher(String.valueOf(toolUseId))
                    .replaceAll("_");

            if (safeId.length() > SAFE_ID_LIMIT) {
                safeId = safeId.substring(
                        0,
                        SAFE_ID_LIMIT
                );
            }

            if (safeId.isEmpty()) {
                safeId = "unknown";
            }

            Path path = toolResultsDir.resolve(
                    safeId + ".txt"
            );

            Files.writeString(
                    path,
                    output,
                    StandardCharsets.UTF_8
            );

            return path;

        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * 落盘并生成留在上下文里的预览块，格式：
     *
     *   <persisted-output>
     *   Full output: {磁盘上的完整输出路径}
     *   Preview:
     *   {内容前 previewChars 字符}
     *   </persisted-output>
     *
     * 输入本身已是占位时（persistedOutputPath 认得出）
     * 直接复用已落盘的文件读预览，不重复写盘；
     * 读失败退回原文截断。
     */
    public String persistedPreview(
            String toolUseId,
            String output,
            int previewChars
    ) {
        Path path;
        String preview;

        String saved = persistedOutputPath(output);

        if (saved != null) {
            path = Path.of(saved);

            try {
                String full = Files.readString(
                        path,
                        StandardCharsets.UTF_8
                );

                preview = full.substring(
                        0,
                        Math.min(
                                full.length(),
                                previewChars
                        )
                );

            } catch (IOException exception) {
                preview = output.substring(
                        0,
                        Math.min(
                                output.length(),
                                previewChars
                        )
                );
            }

        } else {
            path = saveOutput(toolUseId, output);

            preview = output.substring(
                    0,
                    Math.min(
                            output.length(),
                            previewChars
                    )
            );
        }

        return PERSISTED_OPEN
                + FULL_OUTPUT_PREFIX
                + path
                + "\nPreview:\n"
                + preview
                + "\n</persisted-output>";
    }

    /**
     * 最后一条 assistant 消息之后新增的 tool_result 位置集合
     * ——模型还没读过的新批次。
     */
    private static Set<BlockPosition>
    unseenToolResultPositions(List<Message> messages) {

        int lastAssistant = -1;

        for (int index = messages.size() - 1;
             index >= 0;
             index--) {

            if ("assistant".equals(
                    messages.get(index).role())) {
                lastAssistant = index;
                break;
            }
        }

        Set<BlockPosition> unseen = new HashSet<>();

        for (int messageIndex = lastAssistant + 1;
             messageIndex < messages.size();
             messageIndex++) {

            Message message =
                    messages.get(messageIndex);

            if (!"user".equals(message.role())
                    || !message.content().isArray()) {
                continue;
            }

            JsonNode content = message.content();

            for (int blockIndex = 0;
                 blockIndex < content.size();
                 blockIndex++) {

                if (isToolResultBlock(
                        content.get(blockIndex))) {
                    unseen.add(new BlockPosition(
                            messageIndex,
                            blockIndex
                    ));
                }
            }
        }

        return unseen;
    }

    /**
     * 完整历史写 .transcripts/transcript_{uuid}.jsonl
     * （CREATE_NEW，一行一条消息），返回文件路径。
     */
    private Path writeTranscript(List<Message> messages) {
        try {
            Files.createDirectories(transcriptDir);

            Path path = transcriptDir.resolve(
                    "transcript_"
                            + UUID.randomUUID()
                            .toString()
                            .replace("-", "")
                            + ".jsonl"
            );

            try (var writer = Files.newBufferedWriter(
                    path,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW
            )) {
                for (Message message : messages) {
                    writer.write(
                            serializeMessage(message)
                    );
                    writer.write("\n");
                }
            }

            return path;

        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * 归档标记消息：纯文本、格式匹配、
     * 路径仍在 transcript 目录内且文件存在。
     */
    private boolean isArchiveMarker(Message message) {
        JsonNode content = message.content();

        if (!content.isTextual()) {
            return false;
        }

        Matcher matcher = ARCHIVE_MARKER
                .matcher(content.asText());

        if (!matcher.matches()) {
            return false;
        }

        Path path = Path.of(matcher.group(2));

        return isInside(path, transcriptDir)
                && Files.isRegularFile(path);
    }

    private static String serializeMessage(
            Message message
    ) {
        ArrayNode array = MAPPER.createArrayNode();
        ObjectNode node = array.addObject();

        node.put("role", message.role());
        node.set("content", message.content());

        return node.toString();
    }

    private static List<PositionedBlock>
    collectToolResults(List<Message> messages) {

        List<PositionedBlock> results =
                new ArrayList<>();

        for (int messageIndex = 0;
             messageIndex < messages.size();
             messageIndex++) {

            Message message =
                    messages.get(messageIndex);

            if (!"user".equals(message.role())
                    || !message.content().isArray()) {
                continue;
            }

            JsonNode content = message.content();

            for (int blockIndex = 0;
                 blockIndex < content.size();
                 blockIndex++) {

                JsonNode block =
                        content.get(blockIndex);

                if (isToolResultBlock(block)) {
                    results.add(new PositionedBlock(
                            new BlockPosition(
                                    messageIndex,
                                    blockIndex
                            ),
                            (ObjectNode) block
                    ));
                }
            }
        }

        return results;
    }

    /**
     * 从一条消息的数组内容里筛出 tool_result 块：
     * 只认对象节点且 type == "tool_result"，
     * 文本块（todo 提醒这类 rider）自然被排除。
     * 返回的 List 装的是指向原 JSON 树的节点引用
     * ——改它就是改消息本身。
     */
    private static List<ObjectNode> toolResultBlocks(
            JsonNode content
    ) {
        List<ObjectNode> blocks = new ArrayList<>();

        for (JsonNode block : content) {
            if (isToolResultBlock(block)) {
                blocks.add((ObjectNode) block);
            }
        }

        return blocks;
    }

    private static boolean hasToolUse(Message message) {
        if (!"assistant".equals(message.role())
                || !message.content().isArray()) {
            return false;
        }

        for (JsonNode block : message.content()) {
            if (block.isObject()
                    && "tool_use".equals(
                    block.path("type").asText())) {
                return true;
            }
        }

        return false;
    }

    private static boolean isToolResult(Message message) {
        if (!"user".equals(message.role())
                || !message.content().isArray()) {
            return false;
        }

        for (JsonNode block : message.content()) {
            if (isToolResultBlock(block)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isToolResultBlock(
            JsonNode block
    ) {
        return block != null
                && block.isObject()
                && "tool_result".equals(
                block.path("type").asText());
    }

    /**
     * 读 tool_result 块的 content 字段并转成字符串；
     * 字段缺失时给空串（对应参考实现的
     * block.get("content", "")）。注意返回的是
     * 块当前的内容——被替换成预览块后再读，
     * 读到的就是替换后的。
     */
    private static String blockContent(JsonNode block) {
        JsonNode content = block.path("content");

        if (content.isMissingNode()) {
            return "";
        }

        return content.asText();
    }

    /**
     * 读 tool_use_id 作落盘文件名的主干；
     * 缺失时用 "unknown"（对应参考实现的
     * block.get("tool_use_id", "unknown")）。
     * 文件名的清洗（非法字符、长度上限）
     * 由 saveOutput 负责。
     */
    private static String toolUseId(JsonNode block) {
        JsonNode id = block.path("tool_use_id");

        if (id.isMissingNode()) {
            return "unknown";
        }

        return id.asText();
    }

    /**
     * 一批 tool_result 的 content 字符数总和
     * ——预算步的"总量"口径，落盘一块后重算一次。
     */
    private static int sumContents(
            List<ObjectNode> blocks
    ) {
        int total = 0;

        for (ObjectNode block : blocks) {
            total += blockContent(block).length();
        }

        return total;
    }

    /**
     * 单个结果的内容长度——降序排序的比较键，
     * 让最大的结果排在处理队列最前面。
     */
    private static int contentLength(JsonNode block) {
        return blockContent(block).length();
    }

    private static boolean isInside(
            Path path,
            Path directory
    ) {
        return path.toAbsolutePath().normalize()
                .startsWith(
                        directory.toAbsolutePath()
                                .normalize()
                );
    }

    private record BlockPosition(
            int messageIndex,
            int blockIndex
    ) {
    }

    private record PositionedBlock(
            BlockPosition position,
            ObjectNode block
    ) {
    }
}
