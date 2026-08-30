package io.opendoggo.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opendoggo.todo.TodoManager; 
import io.opendoggo.tool.ToolHandler;

public final class TodoWrite implements ToolHandler{

    private final TodoManager todoManager =
            new TodoManager();

    @Override
    public String execute(JsonNode input) {
         JsonNode todos =
                input == null
                        ? null
                        : input.path("todos");
        try {
        return todoManager.update(todos);
        } catch (IllegalArgumentException exception) {
            return "Error: " + exception.getMessage();
        }
    }

    @Override
    public String name() {
        return "todo_write";
    }

    @Override
    public String description() {
        return "Create and manage a task list for "
            + "the current coding session. Use it "
            + "before starting any multi-step task "
            + "to plan the steps first, then keep "
            + "statuses updated as you work. Each "
            + "call replaces the entire list (up "
            + "to 20 items). Each item has content "
            + "and a status of pending, "
            + "in_progress, or completed; only one "
            + "item may be in_progress at a time.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");

        ObjectNode todos = schema.putObject("properties")
             .putObject("todos");
        todos.put("type", "array");
        todos.put("maxItems", 20);

        ObjectNode item = todos.putObject("items");
        item.put("type", "object");

        ObjectNode properties = item.putObject("properties");

        properties.putObject("content")
            .put("type", "string")
            .put("minLength", 1);

        ObjectNode status = properties.putObject("status");
        status.put("type", "string");
        ArrayNode statusEnum = status.putArray("enum");
        statusEnum.add("pending");
        statusEnum.add("in_progress");
        statusEnum.add("completed");

        item.putArray("required")
                .add("content")
                .add("status");

        schema.putArray("required").add("todos");
        return schema;
    }
    
}
