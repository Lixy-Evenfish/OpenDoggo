package io.opendoggo.model;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;

public record Message(String role,JsonNode content) {

    public Message{
        Objects.requireNonNull(role,"role cannot be null");
        Objects.requireNonNull(content,"content cannot be null");

        if(!role.equals("user") && !role.equals("assistant")){
            throw new IllegalArgumentException(
                    "role must be user or assistant"
            );
        }
    }

    public static Message text(String role, String content) {
        Objects.requireNonNull(content, "content cannot be null");
        return new Message(role, TextNode.valueOf(content));
    }

    public static Message user(String content) {
        return text("user", content);
    }

    public static Message assistant(JsonNode content) {
        return new Message("assistant", content);
    }

    /**
     * 模型回复的内容块列表原样写回历史。
     */
    public static Message assistant(List<ContentBlock> blocks) {
        Objects.requireNonNull(blocks, "blocks cannot be null");

        ArrayNode content =
                JsonNodeFactory.instance.arrayNode();

        for (ContentBlock block : blocks) {
            content.add(block.toJson());
        }

        return new Message("assistant", content);
    }

    public static Message toolResults(JsonNode content) {
        return new Message("user", content);
    }

    /**
     * 工具结果以 user 消息返回给模型。
     */
    public static Message toolResults(List<ToolResult> results) {
        Objects.requireNonNull(results, "results cannot be null");

        ArrayNode content =
                JsonNodeFactory.instance.arrayNode();

        for (ToolResult result : results) {
            content.add(result.toJson());
        }

        return new Message("user", content);
    }

}
