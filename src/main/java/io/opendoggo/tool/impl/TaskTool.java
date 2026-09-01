package io.opendoggo.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.opendoggo.agent.AgentLoop;
import io.opendoggo.model.Message;
import io.opendoggo.tool.ToolHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * s06 R1 的 task 工具：把一个明确的子任务
 * 委派给使用全新消息列表的嵌套 Agent Loop。
 *
 * 对父循环而言它只是普通工具——走同一分发表、
 * 同一条 Pre/PostToolUse hook 链，
 * 子代理的最终文本作为 tool_result 返回，
 * 中间对话留在局部消息列表里，不进父上下文。
 */
public final class TaskTool implements ToolHandler {

     
    public static final String STOPPED_MESSAGE =
            "Subagent stopped after 30 turns "
                    + "without a final answer.";
                    
    private final AgentLoop subAgentLoop;

    public TaskTool(AgentLoop subAgentLoop) {
        this.subAgentLoop = Objects.requireNonNull(
                subAgentLoop,
                "subAgentLoop cannot be null"
        );
    }

    @Override
    public String execute(JsonNode input) {
        String prompt =
                input == null
                        ? ""
                        : input.path("prompt").asText("");

        if (prompt.isBlank()) {
            return "Error: prompt is required";
        }

        // 每次调用都是全新消息列表（R2）：
        List<Message> subMessages = new ArrayList<>();
        subMessages.add(Message.user(prompt));

        try {
            // s08 需求2：子任务 prompt 作为 activeRequest 随行
            // ——子循环的压缩/摘要都由它保留本轮请求。
            String text =
                    subAgentLoop.run(subMessages, prompt);

            return text == null || text.isBlank()
                    ? "(no summary)"
                    : text;

        } catch (InterruptedException exception) {
            // 恢复中断标志，转成 Error 结果回父循环。
            Thread.currentThread().interrupt();
            return "Error: interrupted";

        } catch (IOException | RuntimeException exception) {
            // ToolHandler.execute 不抛受检异常；
            // 子循环的失败对父循环只是一次失败的工具调用。
            
            return "Error: " + exception.getMessage();
        }
    }

    @Override
    public String name() {
        return "task";
    }

    @Override
    public String description() {
        return "Run a subagent with fresh "
                + "conversation context and return "
                + "its final text.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema =
                JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");

        schema.putObject("properties")
                .putObject("prompt")
                .put("type", "string")
                .put("minLength", 1);

        schema.putArray("required").add("prompt");
        return schema;
    }

}