package io.opendoggo.model;

import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

public record ContentBlock(
        String type,
        String id,
        String name,
        JsonNode input,
        String text
){
    public ContentBlock {
        Objects.requireNonNull(
                type,
                "type cannot be null"
        );
    }

    public static ContentBlock text(String text) {
        return new ContentBlock(
                "text",
                null,
                null,
                null,
                text
        );
    }

    public static ContentBlock toolUse(
            String id,
            String name,
            JsonNode input
    ) {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(input, "input cannot be null");

        return new ContentBlock(
                "tool_use",
                id,
                name,
                input,
                null
        );
    }

     public boolean isText() {
        return "text".equals(type);
    }

    public boolean isToolUse() {
        return "tool_use".equals(type);
    }

    public ObjectNode toJson() {
        ObjectNode json =
                JsonNodeFactory.instance.objectNode();

        json.put("type", type);

        if (id != null) {
            json.put("id", id);
        }

        if (name != null) {
            json.put("name", name);
        }

        if (input != null) {
            json.set("input", input);
        }

        if (text != null) {
            json.put("text", text);
        }

        return json;
    }
}
