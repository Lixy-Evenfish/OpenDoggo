package io.opendoggo.todo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

public class TodoManager {
    private static final int MAX_TODOS = 20;
    private static final Set<String> STATUSES = Set.of("pending", "in_progress", "completed");
    private record Todo(String content, String status) { }

    private List<Todo> items = new ArrayList<>();

    public String update(JsonNode todos){
        if (todos == null || !todos.isArray()) {
            throw new IllegalArgumentException(
                    "todos must be a list");
        }

        if (todos.size() > MAX_TODOS) {
            throw new IllegalArgumentException(
                    "Max 20 todos allowed");
        }

        List<Todo> validated = new ArrayList<>();
        int inProgressCount = 0;

        for (int i = 0; i < todos.size(); i++){
            JsonNode todo = todos.get(i);
            if (!todo.isObject()) {
                throw new IllegalArgumentException(
                        "todos[" + i + "] must be an object");
            }

            String content = todo
                    .path("content")
                    .asText("")
                    .strip();
            
            if (content.isEmpty()) {
                throw new IllegalArgumentException(
                        "todos[" + i + "] requires content");
            }

            String status = todo
                    .path("status")
                    .asText("pending")
                    .toLowerCase(Locale.ROOT);
            
            if (!STATUSES.contains(status)) {
                throw new IllegalArgumentException(
                        "todos[" + i + "] has invalid status '"
                                + status + "'");
            }

            if ("in_progress".equals(status)) {
                inProgressCount++;
                if (inProgressCount > 1) {
                    throw new IllegalArgumentException("Only one todo can be in_progress at a time");
                }       
            }
            validated.add(new Todo(content, status));
        }
        items = validated;
        return render();
    }
    public String render() {
        if (items.isEmpty()) {
            return "No todos.";
        }
        StringBuilder text = new StringBuilder();
        int done = 0;

        for (Todo todo : items) {
            String marker = switch (todo.status()) {
                case "in_progress" -> "[>]";
                case "completed" -> "[x]";
                default -> "[ ]";
            };

            if ("completed".equals(todo.status())) {
                done++;
            }

            text.append(marker)
                    .append(' ')
                    .append(todo.content())
                    .append('\n');
        
        }

         text.append('\n')
                .append('(')
                .append(done)
                .append('/')
                .append(items.size())
                .append(" completed)");

        return text.toString();
    }
}
