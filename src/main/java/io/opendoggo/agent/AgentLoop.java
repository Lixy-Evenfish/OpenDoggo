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

                String output =
                        toolDispatch.execute(toolCall);

                // s04：执行后通知 PostToolUse hook。
                hookRunner.triggerPostToolUse(
                        toolCall,
                        output
                );

                results.add(new ToolResult(toolCall.id(), output));

            }

            // 工具结果以 user 消息返回给模型。
            messages.add(
                    Message.toolResults(results)
            );
        }

        throw new IllegalStateException(
                "Agent exceeded "
                        + MAX_TOOL_ROUNDS
                        + " tool rounds"
        );
    }

}