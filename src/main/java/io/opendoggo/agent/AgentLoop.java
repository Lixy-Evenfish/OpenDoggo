package io.opendoggo.agent;

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
 */
public final class AgentLoop {

    private static final int MAX_TOOL_ROUNDS = 50;

    private final ModelClient modelClient;
    private final ToolDispatch toolDispatch;

    public AgentLoop(
            ModelClient modelClient,
            ToolDispatch toolDispatch
            
    ) {
        this.modelClient = Objects.requireNonNull(
                modelClient,
                "modelClient cannot be null"
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
            if (toolCalls.isEmpty()) {
                return response.text();
            }

            List<ToolResult> results =
                    new ArrayList<>();

            // 一次模型回复可能包含多个工具调用。
            for (ContentBlock toolCall : toolCalls) {
                System.out.println("> " + toolCall.name());

                String output =
                        toolDispatch.execute(toolCall);

                System.out.println(preview(output));

                results.add(new ToolResult(toolCall.id(),output));

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

    /**
     * 控制台只预览前 200 字符，完整输出仍回传给模型。
     */
    private static String preview(String output) {
        if (output.length() <= 200) {
            return output;
        }

        return output.substring(0, 200);
    }

}