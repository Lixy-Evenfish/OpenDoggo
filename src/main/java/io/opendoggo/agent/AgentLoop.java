package io.opendoggo.agent;

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
import java.util.Objects;

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

    private final ModelClient modelClient;
    private final HookRunner hookRunner;
    private final ToolDispatch toolDispatch;

    public AgentLoop(
            ModelClient modelClient,
            HookRunner hookRunner,
            ToolDispatch toolDispatch
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

    }

    /**
     * 执行一轮用户任务。
     *
     * messages 是可变的完整对话历史。
     */
    public String run(List<Message> messages)
            throws IOException, InterruptedException {

        Objects.requireNonNull(
                messages,
                "messages cannot be null"
        );

        int roundsSinceTodo = 0;
        for (int round = 0;
             round < MAX_TOOL_ROUNDS;
             round++) {

            // 将当前完整历史发送给模型。
            ModelResponse response =
                    modelClient.createMessage(
                            List.copyOf(messages)
                    );

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
            // 一次模型回复可能包含多个工具调用。
            for (ContentBlock toolCall : toolCalls) {
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
                    output = toolDispatch.execute(toolCall);
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
            if (roundsSinceTodo >= TODO_REMINDER_THRESHOLD) {
                reminder = TODO_REMINDER;
                roundsSinceTodo = 0;
            }

            messages.add(Message.toolResults(results, reminder));
        }

        throw new IllegalStateException(
                "Agent exceeded "
                        + MAX_TOOL_ROUNDS
                        + " tool rounds"
        );
    }

}