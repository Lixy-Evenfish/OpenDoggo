package io.opendoggo.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.opendoggo.model.ContentBlock;
import io.opendoggo.model.Message;
import io.opendoggo.model.ModelClient;
import io.opendoggo.model.ModelResponse;
import io.opendoggo.model.ToolResult;
import io.opendoggo.tool.ShellTool;

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
    private final ShellTool shellTool;

    public AgentLoop(
            ModelClient modelClient,
            ShellTool shellTool
    ) {
        this.modelClient = Objects.requireNonNull(
                modelClient,
                "modelClient cannot be null"
        );

        this.shellTool = Objects.requireNonNull(
                shellTool,
                "shellTool cannot be null"
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
                results.add(executeTool(toolCall));
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
     * 分发并执行一个工具调用。
     */
    private ToolResult executeTool(
            ContentBlock toolCall
    ) {
        String result;

        if (!"bash".equals(toolCall.name())) {
            result =
                    "Error: Unknown tool: "
                            + toolCall.name();

            return new ToolResult(
                    toolCall.id(),
                    result
            );
        }

        JsonNode input = toolCall.input();
        JsonNode commandNode =
                input == null
                        ? null
                        : input.get("command");

        if (commandNode == null
                || !commandNode.isTextual()) {
            result =
                    "Error: bash requires "
                            + "a string command";

            return new ToolResult(
                    toolCall.id(),
                    result
            );
        }

        String command = commandNode.asText();

        System.out.println("$ " + command);

        result = shellTool.execute(command);

        // 控制终端预览长度，完整结果仍返回模型。
        System.out.println(
                abbreviate(result, 200)
        );

        return new ToolResult(
                toolCall.id(),
                result
        );
    }

    private String abbreviate(
            String value,
            int maximumLength
    ) {
        if (value.length() <= maximumLength) {
            return value;
        }

        return value.substring(
                0,
                maximumLength
        ) + "...";
    }
}