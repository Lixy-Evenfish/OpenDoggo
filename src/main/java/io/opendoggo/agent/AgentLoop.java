package io.opendoggo.agent;

import io.opendoggo.background.BackgroundManager;
import io.opendoggo.compaction.ContextCompactor;
import io.opendoggo.hook.HookRunner;
import io.opendoggo.model.ContentBlock;
import io.opendoggo.model.Message;
import io.opendoggo.model.ModelClient;
import io.opendoggo.model.ModelResponse;
import io.opendoggo.model.ToolResult;
import io.opendoggo.tool.ToolDispatch;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * S1 的核心 Agent Loop。
 *
 * 模型调用工具时继续循环，不调用工具时结束。
 * s04：循环只调用 hook 挂载点
 * （PreToolUse、PostToolUse、Stop），
 * 具体扩展逻辑全在 HookRunner 注册的回调里，
 * 循环本身不产生任何控制台输出。
 */
public final class AgentLoop {

    private static final int MAX_TOOL_ROUNDS = 50;

    // s05：连续多少个工具轮次没碰 todo_write
    // 就注入催更提醒。
    private static final int TODO_REMINDER_THRESHOLD = 3;

    private static final String TODO_TOOL_NAME = "todo_write";

    private static final String TODO_REMINDER =
            "<reminder>Update your todos.</reminder>";

    // s08 R3：compact 工具——循环内按名拦截
    // （在 PreToolUse hooks 之前），返回字面确认串；
    // 名字与字面量同 tool.impl.CompactTool 保持一致。
    private static final String COMPACT_TOOL_NAME = "compact";

    private static final String COMPACT_RESULT =
            "Compaction requested after this tool batch.";

    private final ModelClient modelClient;
    private final HookRunner hookRunner;
    private final ToolDispatch toolDispatch;

    // s06：可参数化的轮次上限与超限行为——
    // 父循环用默认值（50 轮、超限抛异常），
    // 子循环传 30 轮 + 哨兵字符串（R5）。
    private final int maxToolRounds;
    private final String overrunResult;

    // s06：子循环关闭 todo 催更——
    // 子代理没有 todo_write，提醒只会误导。
    private final boolean todoReminderEnabled;

    // s08：确定性上下文压缩器——
    // null 时关闭压缩（demo / 测试场景）。
    private final ContextCompactor compactor;

    // s11：后台任务管理器——null 时关闭后台执行
    // （demo / 测试场景）。非 null 时 run_in_background
    // 的 bash 调用立即回占位结果，完成结果在后续
    // 轮次模型调用前以通知注入。
    private final BackgroundManager background;

    // s08 需求2：prompt_too_long 补救的最大重试次数。
    private static final int MAX_REACTIVE_RETRIES = 1;

    public AgentLoop(
            ModelClient modelClient,
            HookRunner hookRunner,
            ToolDispatch toolDispatch
    ) {
        this(
                modelClient,
                hookRunner,
                toolDispatch,
                MAX_TOOL_ROUNDS,
                null,
                true,
                null,
                null
        );
    }

    /**
     * s08：父循环便捷构造——带压缩器，
     * 其余保持默认（50 轮、超限抛异常、todo 催更开）。
     */
    public AgentLoop(
            ModelClient modelClient,
            HookRunner hookRunner,
            ToolDispatch toolDispatch,
            ContextCompactor compactor
    ) {
        this(
                modelClient,
                hookRunner,
                toolDispatch,
                MAX_TOOL_ROUNDS,
                null,
                true,
                compactor,
                null
        );
    }

    /**
     * s11：父循环便捷构造——压缩器 + 后台管理器，
     * 其余保持默认（50 轮、超限抛异常、todo 催更开）。
     */
    public AgentLoop(
            ModelClient modelClient,
            HookRunner hookRunner,
            ToolDispatch toolDispatch,
            ContextCompactor compactor,
            BackgroundManager background
    ) {
        this(
                modelClient,
                hookRunner,
                toolDispatch,
                MAX_TOOL_ROUNDS,
                null,
                true,
                compactor,
                background
        );
    }

    /**
     * s06 完整构造器 + s08 压缩器参数。
     *
     * maxToolRounds 覆盖默认的 50 轮上限；
     * overrunResult 非 null 时超限不抛
     * IllegalStateException、直接返回该字符串
     * （子代理的哨兵结论）；todoReminderEnabled
     * 为 false 时关闭 todo 催更提醒；compactor
     * 非 null 时每轮模型调用前先跑压缩管线。
     */
    public AgentLoop(
            ModelClient modelClient,
            HookRunner hookRunner,
            ToolDispatch toolDispatch,
            int maxToolRounds,
            String overrunResult,
            boolean todoReminderEnabled,
            ContextCompactor compactor
    ) {
        this(
                modelClient,
                hookRunner,
                toolDispatch,
                maxToolRounds,
                overrunResult,
                todoReminderEnabled,
                compactor,
                null
        );
    }

    /**
     * s11 完整构造器：在 s06/s08 参数之上增加
     * background（后台任务管理器，null 关闭）。
     *
     * 父/子循环共用同一个 BackgroundManager 实例
     * （与压缩器同口径）——它是宿主级登记处，
     * 完成通知由先到达下一轮模型调用的循环收集。
     */
    public AgentLoop(
            ModelClient modelClient,
            HookRunner hookRunner,
            ToolDispatch toolDispatch,
            int maxToolRounds,
            String overrunResult,
            boolean todoReminderEnabled,
            ContextCompactor compactor,
            BackgroundManager background
    ) {
        this.modelClient = Objects.requireNonNull(
                modelClient,
                "modelClient cannot be null"
        );

        this.hookRunner = Objects.requireNonNull(
                hookRunner,
                "hookRunner cannot be null"
        );

        this.toolDispatch = Objects.requireNonNull(
                toolDispatch,
                "toolDispatch cannot be null"
        );

        this.maxToolRounds = maxToolRounds;
        this.overrunResult = overrunResult;
        this.todoReminderEnabled = todoReminderEnabled;
        this.compactor = compactor;
        this.background = background;
    }

    /**
     * 执行一轮用户任务。
     *
     * messages 是可变的完整对话历史；activeRequest 是
     * 本轮的用户请求原文（s08 需求2）——压缩可能替换掉
     * 承载它的那条消息，请求必须作为参数随行，
     * 压缩多少次都不会丢本轮请求。
     */
    public String run(
            List<Message> messages,
            String activeRequest
    )
            throws IOException, InterruptedException {

        Objects.requireNonNull(
                messages,
                "messages cannot be null"
        );

        int reactiveRetries = 0;
        int roundsSinceTodo = 0;
        for (int round = 0;
             round < maxToolRounds;
             round++) {

            // s08：每次调用模型前先跑压缩管线
            // （确定性整理 + 仍超限时的历史摘要）。
            if (compactor != null) {
                compactor.prepare(messages, activeRequest);
            }

            // s11：模型调用前收集已完成的后台任务，
            // 以通知并入最近一条 user 消息——
            // 后台结果不主动唤醒循环，只搭下一班车。
            if (background != null) {
                injectBackgroundResults(messages);
            }

            // 将当前完整历史发送给模型。
            // s08 需求2：字符估算漏判、API 拒绝
            // （prompt_too_long / too many tokens）时，
            // 保留最近 5 条做一次响应式压缩后重试。
            ModelResponse response;

            try {
                response = modelClient.createMessage(
                        List.copyOf(messages)
                );

                reactiveRetries = 0;

            } catch (IOException | RuntimeException exception) {
                String errorText = String.valueOf(
                        exception.getMessage()
                ).toLowerCase(Locale.ROOT);

                boolean tooLong =
                        errorText.contains("prompt_too_long")
                                || errorText.contains(
                                "too many tokens"
                        );

                if (compactor != null
                        && tooLong
                        && reactiveRetries
                        < MAX_REACTIVE_RETRIES) {

                    compactor.reactiveCompact(
                            messages,
                            activeRequest
                    );

                    reactiveRetries++;
                    continue;
                }

                throw exception;
            }

            // 模型回复必须先完整加入历史。
            messages.add(
                    Message.assistant(response.content())
            );

            List<ContentBlock> toolCalls =
                    response.toolCalls();

            // 没有工具调用，说明模型决定结束。
            // s04：Stop hook 有权拒绝退出——
            // 非 null 返回值注入为 user 消息并继续。
            if (toolCalls.isEmpty()) {
                String force =
                        hookRunner.triggerStop(messages);

                if (force != null) {
                    messages.add(Message.user(force));
                    continue;
                }

                return response.text();
            }

            List<ToolResult> results =
                    new ArrayList<>();
            boolean usedTodo = false;
            boolean compactRequested = false;
            // 一次模型回复可能包含多个工具调用。
            for (ContentBlock toolCall : toolCalls) {
                // s08 R3：compact 在 hooks 之前拦截——
                // 不经过权限检查与 dispatch（参考实现
                // 不让 execute_tool 见到它），直接回
                // 字面确认串；真正的压缩等整批结果
                // 入史后再执行。
                if (COMPACT_TOOL_NAME.equals(
                        toolCall.name())) {
                    results.add(new ToolResult(
                            toolCall.id(),
                            COMPACT_RESULT
                    ));
                    compactRequested = true;
                    continue;
                }

                // s04：拦截决定权交给 PreToolUse hook；
                // 非 null 即拦截原因，
                // 拒绝也要回带原 id 的 tool_result。
                String blocked =
                        hookRunner.triggerPreToolUse(toolCall);

                if (blocked != null) {
                    results.add(
                            new ToolResult(
                                    toolCall.id(),
                                    blocked
                            )
                    );
                    continue;
                }

                // s05：工具异常不再炸掉循环，
                // 转成 Error 工具结果回传给模型。
                String output;

                try {
                    // s11：显式 run_in_background 的 bash
                    // 进后台线程——此刻 PreToolUse（权限）
                    // 已经放行，立即回带 bg_id 的占位结果，
                    // 命令的真实结果留给后续轮次收集；
                    // 其余调用照常同步执行。
                    if (background != null
                            && background.isBackgroundRequest(
                            toolCall.name(),
                            toolCall.input()
                    )) {
                        String taskId = background.start(
                                toolCall.name(),
                                toolCall.id(),
                                toolCall.input()
                        );

                        output = "[Background task "
                                + taskId
                                + " started] The result "
                                + "will be collected on a "
                                + "later turn.";

                    } else {
                        output = toolDispatch.execute(
                                toolCall
                        );
                    }

                } catch (Exception exception) {
                    output = "Error: " + exception.getMessage();
                }

                // s05：内容统一转字符串，
                // 防止 null 进入 tool_result
                // （ToolResult 的 content 是非空约束）。
                output = String.valueOf(output);

                // s04：执行后通知 PostToolUse hook。
                hookRunner.triggerPostToolUse(
                        toolCall,
                        output
                );
                if (TODO_TOOL_NAME.equals(toolCall.name())) {
                    usedTodo = true;
                }
                results.add(new ToolResult(toolCall.id(), output));

            }
            roundsSinceTodo = usedTodo ? 0 : roundsSinceTodo + 1;

            String reminder = null;
            if (todoReminderEnabled
                    && roundsSinceTodo
                            >= TODO_REMINDER_THRESHOLD) {
                reminder = TODO_REMINDER;
                roundsSinceTodo = 0;
            }

            messages.add(
                    Message.toolResults(results, reminder)
            );

            // s08 R3：整批结果闭合（每个 tool_use 都有
            // 配对结果）之后才压缩——已发生的副作用
            // （文件写入等）进入摘要而不是被丢弃，
            // 模型不会重复执行同一批操作。
            if (compactRequested && compactor != null) {
                compactor.compactHistory(
                        messages,
                        activeRequest
                );
            }
        }

        // s06：overrunResult 非 null 时（子循环）
        // 超限不抛异常，哨兵字符串作为本轮结论返回
        // ——是一次正常的 tool_result，不是崩溃；
        // 为 null 时（父循环）保持原行为。
        if (overrunResult != null) {
            return overrunResult;
        }

        throw new IllegalStateException(
                "Agent exceeded "
                        + maxToolRounds
                        + " tool rounds"
        );
    }

    /**
     * s11：把已完成的后台结果并入消息列表
     * （对应参考实现 inject_background_results）。
     *
     * 通知以 type=text 块加入最近一条 user 消息：
     * 数组内容原地追加（工具结果批次搭车，同
     * todo 提醒的 rider 做法）；纯文本内容升级为
     * 块数组（本轮 query + 通知同条消息）；
     * 最近一条不是 user 时（上轮以 assistant 收尾）
     * 另起一条 user 消息。通知是独立文本事件，
     * 不复用 tool_use_id，一个 tool_use 仍然只
     * 对应一个 tool_result。
     *
     * @return 本次注入的通知条数
     */
    private int injectBackgroundResults(
            List<Message> messages
    ) {
        List<String> notifications = background.collect();

        if (notifications.isEmpty()) {
            return 0;
        }

        ArrayNode blocks =
                JsonNodeFactory.instance.arrayNode();

        for (String notification : notifications) {
            blocks.addObject()
                    .put("type", "text")
                    .put("text", notification);
        }

        if (!messages.isEmpty()
                && "user".equals(messages.get(
                messages.size() - 1).role())) {

            Message last = messages.get(
                    messages.size() - 1
            );

            if (last.content().isArray()) {
                ((ArrayNode) last.content())
                        .addAll(blocks);

            } else {
                ArrayNode merged =
                        JsonNodeFactory.instance.arrayNode();

                merged.addObject()
                        .put("type", "text")
                        .put("text",
                                last.content().asText());
                merged.addAll(blocks);

                messages.set(
                        messages.size() - 1,
                        new Message("user", merged)
                );
            }

        } else {
            messages.add(new Message("user", blocks));
        }

        return notifications.size();
    }

}