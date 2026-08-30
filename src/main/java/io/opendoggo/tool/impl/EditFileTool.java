package io.opendoggo.tool.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.opendoggo.tool.ToolHandler;

/**
 * 把文件中第一处 old_text 精确替换为 new_text。
 */
public final class EditFileTool implements ToolHandler {

    private final Path workingDirectory;

    public EditFileTool(Path workingDirectory) {
        this.workingDirectory =
                Objects.requireNonNull(
                        workingDirectory,
                        "workingDirectory cannot be null"
                )
                        .toAbsolutePath()
                        .normalize();
    }

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String description() {
        return "Replace exact text in a file once.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema =
                JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");

        ObjectNode properties =
                schema.putObject("properties");
        properties.putObject("path")
                .put("type", "string");
        properties.putObject("old_text")
                .put("type", "string");
        properties.putObject("new_text")
                .put("type", "string");

        schema.putArray("required")
                .add("path")
                .add("old_text")
                .add("new_text");
        return schema;
    }

    @Override
    public String execute(JsonNode input) {
        if (input == null) {
            return "Error: path, old_text, "
                    + "new_text are required";
        }

        String rawPath = input.path("path").asText(null);

        Path path = WorkspacePaths.resolveAny(
                workingDirectory,
                rawPath
        );

        if (path == null) {
            return "Error: path cannot be empty";
        }

        String oldText = input.path("old_text").asText("");
        String newText = input.path("new_text").asText("");

        // 空串会匹配到位置 0，变成在文件头插入，必须拒绝。
        if (oldText.isEmpty()) {
            return "Error: old_text cannot be empty";
        }

        String text;
        try {
            text = Files.readString(
                    path,
                    StandardCharsets.UTF_8
            );

        } catch (IOException exception) {
            return "Error: " + exception.getMessage();
        }

        int index = text.indexOf(oldText);
        if (index < 0) {
            return "Error: text not found in "
                    + rawPath.strip();
        }

        // 只替换第一处，等价于 Python 的 replace(old, new, 1)。
        String updated = text.substring(0, index)
                + newText
                + text.substring(index + oldText.length());

        try {
            Files.writeString(
                    path,
                    updated,
                    StandardCharsets.UTF_8
            );

        } catch (IOException exception) {
            return "Error: " + exception.getMessage();
        }

        return "Edited " + rawPath.strip();
    }
}
