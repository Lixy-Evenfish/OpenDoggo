package io.opendoggo.model;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * 一次本地工具调用的执行结果。
 */
public record ToolResult(
        String toolUseId,
        String content
) {

    public ToolResult {
        Objects.requireNonNull(
                toolUseId,
                "toolUseId cannot be null"
        );

        Objects.requireNonNull(
                content,
                "content cannot be null"
        );
    }

    /**
     * tool_use_id 必须与模型返回的工具调用 ID 一致。
     */
    public ObjectNode toJson() {
        ObjectNode json =
                JsonNodeFactory.instance.objectNode();

        json.put("type", "tool_result");
        json.put("tool_use_id", toolUseId);
        json.put("content", content);

        return json;
    }
}