package io.opendoggo.demo;

import io.opendoggo.agent.AgentLoop;
import io.opendoggo.compaction.ContextCompactor;
import io.opendoggo.hook.HookRunner;
import io.opendoggo.model.ContentBlock;
import io.opendoggo.model.Message;
import io.opendoggo.model.ModelClient;
import io.opendoggo.model.ModelResponse;
import io.opendoggo.model.ToolResult;
import io.opendoggo.tool.ToolDispatch;
import io.opendoggo.tool.ToolHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * s08 验收 demo（需求1 + 需求2 + 需求3）：在临时目录里构造
 * 消息历史，逐项自检压缩管线——toolResultBudget /
 * snipCompact（含配对保护与幂等）/ microCompact /
 * fitToolResults / prepare 端到端 / 占位路径识别，
 * compactHistory（请求/摘要分区 + JSON 转义 +
 * transcript 留档 + 80k 输入截断）、reactiveCompact
 * （保留 5 条 + 配对保护）、整理无效时的自动摘要，
 * 以及 R3 的 compact 工具拦截（脚本 ModelClient
 * 驱动真实 AgentLoop）。摘要器用 lambda 脚本替代，
 * 不依赖 API key，不进 REPL。
 */
public final class CompactionDemo {

    private static final String SAVED_AT_PREFIX =
            "[Earlier tool result saved at ";

    private CompactionDemo() {
    }

    public static void main(String[] args) throws Exception {
        Path base =
                Files.createTempDirectory("compact-demo");

        // 需求2：脚本摘要器——捕获输入，返回固定摘要。
        String[] capturedSummaryInput = new String[1];

        ContextCompactor compactor =
                new ContextCompactor(
                        base.resolve(".transcripts"),
                        base.resolve(".task_outputs")
                                .resolve("tool-results"),
                        conversationJson -> {
                            capturedSummaryInput[0] =
                                    conversationJson;

                            return "Goal: demo task. "
                                    + "Files: none. "
                                    + "Remaining: finish.";
                        }
                );

        budgetCheck(compactor);
        snipCheck(compactor);
        microCheck(compactor);
        fitCheck(compactor);
        prepareCheck(compactor);
        placeholderCheck(compactor);
        compactHistoryCheck(compactor, capturedSummaryInput);
        reactiveCheck(compactor, capturedSummaryInput);
        autoCompactCheck(compactor);
        compactToolCheck(compactor);

        System.out.println();
        System.out.println(
                "CompactionDemo: 全部自检通过"
        );
    }

    /**
     * 预算步：批次总量 230k 超 200k，
     * 最大的 150k 结果落盘换预览后总量即回到限内——
     * 80k 与小结果不再动。
     */
    private static void budgetCheck(
            ContextCompactor compactor
    ) throws IOException {
        System.out.println(
                "== 1) toolResultBudget: "
                        + "230k 批次只落盘最大的 150k =="
        );

        String big1 = "x".repeat(150_000);
        String big2 = "y".repeat(80_000);

        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("run"));
        messages.add(assistantToolUse("a1", "a2", "a3"));
        messages.add(Message.toolResults(List.of(
                new ToolResult("a1", big1),
                new ToolResult("a2", big2),
                new ToolResult("a3", "z")
        )));

        compactor.toolResultBudget(messages);

        JsonNode blocks =
                messages.get(2).content();

        String first =
                blocks.get(0)
                        .path("content")
                        .asText();

        check(
                first.startsWith(
                        "<persisted-output>\n"
                ),
                "150k 结果替换为 persisted-output 预览块"
        );

        check(
                first.contains("Full output: "),
                "预览块带完整输出路径"
        );

        String saved =
                compactor.persistedOutputPath(first);

        check(
                saved != null
                        && Files.isRegularFile(
                        Path.of(saved)
                ),
                "落盘文件存在且路径可回查"
        );

        check(
                Files.readString(
                        Path.of(saved),
                        StandardCharsets.UTF_8
                ).length() == 150_000,
                "落盘内容完整（150k）"
        );

        check(
                blocks.get(1)
                        .path("content")
                        .asText()
                        .equals(big2),
                "总量回到限内，80k 结果不再处理"
        );

        check(
                blocks.get(2)
                        .path("content")
                        .asText()
                        .equals("z"),
                "小结果原样保留"
        );
    }

    /**
     * 归档步：60 条 -> 头 4（配对保护吞掉 tool_result）
     * + 标记 + 尾 46；再跑一次幂等。
     */
    private static void snipCheck(
            ContextCompactor compactor
    ) throws IOException {
        System.out.println(
                "== 2) snipCompact: "
                        + "60 条归档中段，配对保护 + 幂等 =="
        );

        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("start"));
        messages.add(Message.user("context"));
        messages.add(assistantToolUse("t1"));
        messages.add(toolResultMessage("t1", "ok"));

        for (int index = 4;
             index < 60;
             index++) {
            messages.add(
                    Message.user("msg-" + index)
            );
        }

        compactor.snipCompact(messages);

        check(
                messages.size() == 51,
                "60 条 -> 4(头) + 1(标记) + 46(尾) = 51"
        );

        Message marker = messages.get(4);

        check(
                marker.content().isTextual(),
                "归档标记是纯文本 user 消息"
        );

        String text = marker.content().asText();

        check(
                text.startsWith(
                        "[10 messages archived at "
                ),
                "头切点后移 1 条（保护 tool_use 配对），归档 10 条"
        );

        String transcriptPath = text.substring(
                text.indexOf(" at ") + 4,
                text.length() - 1
        );

        check(
                Files.isRegularFile(
                        Path.of(transcriptPath)
                ),
                "transcript 文件存在"
        );

        check(
                Files.readAllLines(
                        Path.of(transcriptPath),
                        StandardCharsets.UTF_8
                ).size() == 60,
                "transcript 留档完整 60 行"
        );

        check(
                "tool_use".equals(
                        messages.get(2)
                                .content()
                                .get(0)
                                .path("type")
                                .asText()
                ),
                "头部的 tool_use 完整保留"
        );

        check(
                "tool_result".equals(
                        messages.get(3)
                                .content()
                                .get(0)
                                .path("type")
                                .asText()
                ),
                "配对的 tool_result 不孤儿"
        );

        compactor.snipCompact(messages);

        check(
                messages.size() == 51,
                "第二次执行幂等（中段只剩旧标记）"
        );
    }

    /**
     * micro 步：5 条已读结果换掉最早 2 条、
     * 保留最近 3 条；最后一批未读结果不动。
     */
    private static void microCheck(
            ContextCompactor compactor
    ) throws IOException {
        System.out.println(
                "== 3) microCompact: "
                        + "已读旧结果换路径引用，保留最近 3 条 =="
        );

        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("task"));
        messages.add(assistantToolUse(
                "a1", "a2", "a3", "a4", "a5"
        ));

        List<ToolResult> results = new ArrayList<>();

        for (int index = 1;
             index <= 5;
             index++) {
            results.add(new ToolResult(
                    "a" + index,
                    ("r" + index)
                            + "x".repeat(200)
            ));
        }

        messages.add(Message.toolResults(results));
        messages.add(assistantToolUse("a6"));
        messages.add(toolResultMessage(
                "a6",
                "new" + "y".repeat(500)
        ));

        // target 传 null：处理全部候选，便于逐块断言。
        compactor.microCompact(messages, null);

        JsonNode seen =
                messages.get(2).content();

        for (int index = 0;
             index <= 1;
             index++) {
            String content = seen.get(index)
                    .path("content")
                    .asText();

            check(
                    content.startsWith(SAVED_AT_PREFIX)
                            && content.endsWith("]"),
                    "a" + (index + 1)
                            + " 替换为路径引用"
            );

            String path = content.substring(
                    SAVED_AT_PREFIX.length(),
                    content.length() - 1
            );

            check(
                    Files.isRegularFile(Path.of(path)),
                    "a" + (index + 1) + " 全文已落盘"
            );
        }

        for (int index = 2;
             index <= 4;
             index++) {
            check(
                    seen.get(index)
                            .path("content")
                            .asText()
                            .startsWith("r" + (index + 1)),
                    "a" + (index + 1)
                            + " 属于最近 3 条，原样保留"
            );
        }

        check(
                messages.get(4)
                        .content()
                        .get(0)
                        .path("content")
                        .asText()
                        .startsWith("new"),
                "未读结果（最新批次）不动"
        );
    }

    /**
     * fit 步：单个 60k 未读结果换成
     * 1,000 字符预览，估算回到目标内。
     */
    private static void fitCheck(
            ContextCompactor compactor
    ) throws IOException {
        System.out.println(
                "== 4) fitToolResults: "
                        + "未读大结果换 1,000 字符预览 =="
        );

        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("go"));
        messages.add(assistantToolUse("big"));
        messages.add(toolResultMessage(
                "big",
                "f".repeat(60_000)
        ));

        compactor.fitToolResults(messages, 40_000);

        String content = messages.get(2)
                .content()
                .get(0)
                .path("content")
                .asText();

        check(
                content.startsWith(
                        "<persisted-output>\n"
                ),
                "60k 结果替换为预览块"
        );

        check(
                content.length() < 2_000,
                "预览约 1,000 字符，远小于原文"
        );

        String saved =
                compactor.persistedOutputPath(content);

        check(
                saved != null
                        && Files.readString(
                        Path.of(saved),
                        StandardCharsets.UTF_8
                ).length() == 60_000,
                "全文 60k 落盘可恢复"
        );

        check(
                compactor.estimateChars(messages)
                        <= 40_000,
                "整理后估算回到目标内"
        );
    }

    /**
     * 端到端：六轮 30k 结果 + 收尾文本。
     * 预算步不动（末条是 assistant）；micro 换掉
     * 最早 3 条；fit 再换 2 条预览后回到限内，
     * 最近 1 条完整保留——全程零 API 调用。
     */
    private static void prepareCheck(
            ContextCompactor compactor
    ) {
        System.out.println(
                "== 5) prepare 端到端: "
                        + "180k 历史 -> 50k 以内 =="
        );

        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("big task"));

        for (int index = 1;
             index <= 6;
             index++) {
            messages.add(
                    assistantToolUse("b" + index)
            );
            messages.add(toolResultMessage(
                    "b" + index,
                    "f".repeat(30_000)
            ));
        }

        messages.add(
                Message.text("assistant", "done")
        );

        check(
                compactor.estimateChars(messages)
                        > ContextCompactor
                        .CONTEXT_CHAR_LIMIT,
                "初始估算超限（约 180k）"
        );

        compactor.prepare(messages, "big task");

        check(
                compactor.estimateChars(messages)
                        <= ContextCompactor
                        .CONTEXT_CHAR_LIMIT,
                "整理后估算 <= 50,000"
        );

        for (int index = 1;
             index <= 3;
             index++) {
            check(
                    messages.get(2 * index)
                            .content()
                            .get(0)
                            .path("content")
                            .asText()
                            .startsWith(SAVED_AT_PREFIX),
                    "b" + index
                            + " 已读旧结果 -> 路径引用"
            );
        }

        for (int index = 4;
             index <= 5;
             index++) {
            check(
                    messages.get(2 * index)
                            .content()
                            .get(0)
                            .path("content")
                            .asText()
                            .startsWith(
                                    "<persisted-output>"
                            ),
                    "b" + index
                            + " 换 1,000 字符预览"
            );
        }

        check(
                messages.get(12)
                        .content()
                        .get(0)
                        .path("content")
                        .asText()
                        .equals("f".repeat(30_000)),
                "b6（最近的已读结果）完整保留"
        );
    }

    /**
     * 占位识别：两种格式能找回目录内的真实路径，
     * 目录外 / 普通文本不误判。
     */
    private static void placeholderCheck(
            ContextCompactor compactor
    ) throws IOException {
        System.out.println(
                "== 6) persistedOutputPath: "
                        + "占位识别与目录外拒绝 =="
        );

        Path saved =
                compactor.saveOutput("check-1", "hello");

        check(
                compactor.persistedOutputPath(
                        SAVED_AT_PREFIX
                                + saved
                                + "]"
                ) != null,
                "saved-at 占位能找回路径"
        );

        String preview = compactor.persistedPreview(
                "check-2",
                "0123456789".repeat(100),
                10
        );

        String fromPreview =
                compactor.persistedOutputPath(preview);

        check(
                fromPreview != null
                        && Files.isRegularFile(
                        Path.of(fromPreview)
                ),
                "预览块能找回路径"
        );

        check(
                compactor.persistedOutputPath(
                        SAVED_AT_PREFIX
                                + "/etc/passwd]"
                ) == null,
                "目录外路径拒绝"
        );

        check(
                compactor.persistedOutputPath(
                        "plain text"
                ) == null,
                "普通文本不误判"
        );
    }

    /**
     * 需求2：compactHistory——整段历史换成单条
     * [Compacted] 消息；请求与摘要分区、摘要 JSON 转义、
     * transcript 全量留档；超 80k 的摘要输入掐头去尾。
     */
    private static void compactHistoryCheck(
            ContextCompactor compactor,
            String[] capturedSummaryInput
    ) throws IOException {
        System.out.println(
                "== 7) compactHistory: 历史 -> 单条 [Compacted] 消息 =="
        );

        List<Message> messages = new ArrayList<>();
        messages.add(Message.user("分析这个项目"));
        messages.add(assistantToolUse("c1"));
        messages.add(toolResultMessage(
                "c1",
                "detail-" + "x".repeat(300)
        ));
        messages.add(Message.text("assistant", "部分结论"));

        compactor.compactHistory(messages, "分析这个项目");

        check(
                messages.size() == 1,
                "历史替换为单条消息"
        );

        check(
                "user".equals(messages.get(0).role()),
                "摘要消息是 user 角色"
        );

        String text = messages.get(0).content().asText();

        check(
                text.startsWith("[Compacted]\n\n"),
                "以 [Compacted] 标签开头"
        );

        check(
                text.contains(
                        "Current user request:\n分析这个项目"
                ),
                "当前请求单独分区保留"
        );

        check(
                text.contains(
                        "Conversation summary (reference only):\n"
                                + "\"Goal: demo task. Files: none. "
                                + "Remaining: finish.\""
                ),
                "摘要是 JSON 转义（带引号）形式"
        );

        int markerIndex =
                text.indexOf("Full transcript: ");

        String transcriptPath = text.substring(
                markerIndex + "Full transcript: ".length()
        );

        check(
                Files.isRegularFile(Path.of(transcriptPath)),
                "transcript 全量留档存在"
        );

        check(
                Files.readAllLines(
                        Path.of(transcriptPath),
                        StandardCharsets.UTF_8
                ).size() == 4,
                "transcript 留档完整 4 行"
        );

        check(
                capturedSummaryInput[0] != null
                        && capturedSummaryInput[0]
                        .contains("分析这个项目"),
                "摘要输入是整段对话 JSON"
        );

        // 超 80k 的输入触发掐头去尾拼接。
        List<Message> big = new ArrayList<>();

        for (int index = 0;
             index < 6;
             index++) {
            big.add(Message.user(
                    "line-" + index + "-"
                            + "y".repeat(20_000)
            ));
        }

        compactor.compactHistory(big, "大输入");

        check(
                capturedSummaryInput[0].length()
                        <= ContextCompactor
                        .SUMMARY_INPUT_CHAR_LIMIT + 100,
                "摘要输入截到 80k 左右"
        );

        check(
                capturedSummaryInput[0].contains(
                        "[middle omitted; full transcript "
                                + "is on disk]"
                ),
                "中段省略标记存在"
        );
    }

    /**
     * 需求2：reactiveCompact——保留最近 5 条
     * （配对保护把配对的 tool_use 也留下），
     * 更早历史进 [Reactive compact] 摘要。
     */
    private static void reactiveCheck(
            ContextCompactor compactor,
            String[] capturedSummaryInput
    ) {
        System.out.println(
                "== 8) reactiveCompact: 保留最近 5 条 + 配对保护 =="
        );

        List<Message> messages = new ArrayList<>();

        for (int index = 0;
             index < 6;
             index++) {
            messages.add(Message.user("old-" + index));
        }

        messages.add(assistantToolUse("r1"));
        messages.add(toolResultMessage("r1", "rr"));

        messages.add(Message.user("after-1"));
        messages.add(Message.user("after-2"));
        messages.add(Message.user("after-3"));
        messages.add(Message.user("after-4"));

        compactor.reactiveCompact(messages, "keep me");

        check(
                messages.size() == 7,
                "摘要 1 条 + 保留 6 条（配对保护多留 tool_use）"
        );

        String text = messages.get(0).content().asText();

        check(
                text.startsWith("[Reactive compact]\n\n"),
                "Reactive compact 标签"
        );

        check(
                text.contains("Current user request:\nkeep me"),
                "当前请求保留"
        );

        check(
                "tool_use".equals(
                        messages.get(1)
                                .content()
                                .get(0)
                                .path("type")
                                .asText()
                ),
                "配对的 tool_use 留在保留区"
        );

        check(
                "tool_result".equals(
                        messages.get(2)
                                .content()
                                .get(0)
                                .path("type")
                                .asText()
                ),
                "配对完整，无孤儿结果"
        );

        check(
                messages.get(6)
                        .content()
                        .asText()
                        .equals("after-4"),
                "最近的文本消息保留"
        );

        check(
                capturedSummaryInput[0].contains("old-0")
                        && !capturedSummaryInput[0]
                        .contains("after-4"),
                "更早历史进摘要，保留区不进"
        );
    }

    /**
     * 需求2端到端：确定性步骤无法缩小的历史
     * （长文本消息、无工具结果）必然触发第四步
     * ——历史换成单条 [Compacted] 消息，
     * activeRequest 经参数保留。
     */
    private static void autoCompactCheck(
            ContextCompactor compactor
    ) {
        System.out.println(
                "== 9) prepare 端到端: 整理无效 -> 自动摘要 =="
        );

        List<Message> messages = new ArrayList<>();

        for (int index = 0;
             index < 8;
             index++) {
            messages.add(Message.user(
                    "text-" + index + "-"
                            + "z".repeat(15_000)
            ));
        }

        check(
                compactor.estimateChars(messages)
                        > ContextCompactor.CONTEXT_CHAR_LIMIT,
                "初始估算约 120k，超限"
        );

        compactor.prepare(messages, "本轮的最终请求");

        check(
                messages.size() == 1,
                "触发第四步，历史替换为单条消息"
        );

        check(
                messages.get(0)
                        .content()
                        .asText()
                        .contains(
                                "Current user request:\n本轮的最终请求"
                        ),
                "activeRequest 经参数保留"
        );

        check(
                compactor.estimateChars(messages)
                        <= ContextCompactor.CONTEXT_CHAR_LIMIT,
                "摘要后估算回到限内"
        );
    }

    /**
     * R3：compact 工具的循环内拦截——脚本 ModelClient
     * 驱动真实 AgentLoop 两轮：第 1 轮返回
     * [echo 调用 + compact 调用]，第 2 轮纯文本收尾。
     * 验证：compact 不经过 PreToolUse hooks（echo 照常）；
     * 整批结果闭合后才压缩——第 2 轮模型收到的
     * 恰好是单条 [Compacted] 消息。
     */
    private static void compactToolCheck(
            ContextCompactor compactor
    ) throws IOException, InterruptedException {
        System.out.println(
                "== 10) compact 工具: 循环内拦截 + 整批后压缩 =="
        );

        ObjectNode echoInput =
                JsonNodeFactory.instance.objectNode();
        echoInput.put("note", "执行一个无害工具");

        List<ModelResponse> responses = List.of(
                new ModelResponse(List.of(
                        ContentBlock.text(
                                "先执行工具，再请求压缩"
                        ),
                        ContentBlock.toolUse(
                                "e1",
                                "echo",
                                echoInput
                        ),
                        ContentBlock.toolUse(
                                "c1",
                                "compact",
                                JsonNodeFactory.instance
                                        .objectNode()
                        )
                )),
                new ModelResponse(List.of(
                        ContentBlock.text("完成")
                ))
        );

        ScriptedModel model =
                new ScriptedModel(responses);

        List<String> preToolUseSeen =
                new ArrayList<>();

        HookRunner hooks = new HookRunner();
        hooks.registerPreToolUse(toolCall -> {
            preToolUseSeen.add(toolCall.name());
            return null;
        });

        ToolDispatch dispatch = new ToolDispatch();
        dispatch.register(echoHandler());

        AgentLoop loop = new AgentLoop(
                model,
                hooks,
                dispatch,
                compactor
        );

        List<Message> history = new ArrayList<>();
        history.add(Message.user("测试 R3 拦截"));

        String answer = loop.run(history, "测试 R3 拦截");

        check(
                "完成".equals(answer),
                "循环正常结束，返回最终文本"
        );

        check(
                preToolUseSeen.contains("echo")
                        && !preToolUseSeen.contains("compact"),
                "echo 走 hooks，compact 被拦在 hooks 之前"
        );

        check(
                history.size() == 2,
                "历史 = [Compacted] + 最终 assistant，共 2 条"
        );

        check(
                history.get(0)
                        .content()
                        .isTextual()
                        && history.get(0)
                        .content()
                        .asText()
                        .startsWith("[Compacted]\n\n"),
                "第一条历史是 [Compacted] 摘要消息"
        );

        check(
                history.get(0)
                        .content()
                        .asText()
                        .contains(
                                "Current user request:\n测试 R3 拦截"
                        ),
                "activeRequest 保留在摘要消息里"
        );

        List<Message> secondCall =
                model.received().get(1);

        check(
                secondCall.size() == 1
                        && secondCall.get(0)
                        .content()
                        .asText()
                        .startsWith("[Compacted]"),
                "第 2 轮模型恰好收到单条 [Compacted] 消息（批次已闭合后才压缩）"
        );
    }

    /** 一个无害的演示工具：原样回显 note。 */
    private static ToolHandler echoHandler() {
        return new ToolHandler() {

            @Override
            public String execute(JsonNode input) {
                return "echo: "
                        + input.path("note").asText("");
            }

            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "Echo the note back.";
            }

            @Override
            public JsonNode inputSchema() {
                ObjectNode schema =
                        JsonNodeFactory.instance
                                .objectNode();
                schema.put("type", "object");
                schema.putObject("properties")
                        .putObject("note")
                        .put("type", "string");
                return schema;
            }
        };
    }

    /** 脚本模型：按序吐出预设回复，记录每次收到的消息。 */
    private static final class ScriptedModel
            implements ModelClient {

        private final List<ModelResponse> responses;

        private final List<List<Message>> received =
                new ArrayList<>();

        private int index;

        private ScriptedModel(
                List<ModelResponse> responses
        ) {
            this.responses = responses;
        }

        List<List<Message>> received() {
            return received;
        }

        @Override
        public ModelResponse createMessage(
                List<Message> messages
        ) {
            received.add(messages);
            return responses.get(index++);
        }
    }

    private static Message assistantToolUse(
            String... ids
    ) {
        ArrayNode content =
                JsonNodeFactory.instance.arrayNode();

        for (String id : ids) {
            var block = content.addObject();
            block.put("type", "tool_use");
            block.put("id", id);
            block.put("name", "bash");
            block.set(
                    "input",
                    JsonNodeFactory.instance
                            .objectNode()
            );
        }

        return Message.assistant(content);
    }

    private static Message toolResultMessage(
            String id,
            String content
    ) {
        ArrayNode array =
                JsonNodeFactory.instance.arrayNode();

        var block = array.addObject();
        block.put("type", "tool_result");
        block.put("tool_use_id", id);
        block.put("content", content);

        return Message.toolResults(array);
    }

    private static void check(
            boolean condition,
            String label
    ) {
        if (!condition) {
            throw new AssertionError(
                    "检查失败: " + label
            );
        }

        System.out.println("  ok - " + label);
    }
}
