package io.opendoggo.tool.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.opendoggo.tool.ToolHandler;

/**
 * 读取工作区内文件内容，可用 limit 截取前若干行。
 */
public final class ReadFileTool implements ToolHandler {

    private final Path workingDirectory;

    public ReadFileTool(Path workingDirectory) {
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
        return "read_file";
    }

    @Override
    public String description() {
        return "Read file contents.";
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
        properties.putObject("limit")
                .put("type", "integer");

        schema.putArray("required").add("path");
        return schema;
    }

    @Override
    public String execute(JsonNode input) {
        String rawPath =
                input == null
                        ? null
                        : input.path("path").asText(null);

        Path path = WorkspacePaths.resolveIn(
                workingDirectory,
                rawPath
        );

        if (path == null) {
            return "Error: path escapes workspace: "
                    + rawPath;
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(
                    path,
                    StandardCharsets.UTF_8
            );

        } catch (IOException exception) {
            return "Error: " + exception.getMessage();
        }

        JsonNode limitNode = input.path("limit");
        int limit = limitNode.isNumber()
                ? limitNode.asInt()
                : 0;

        // limit <= 0 视为不限制，与参考实现一致。
        if (limit > 0 && limit < lines.size()) {
            List<String> shown = new ArrayList<>(
                    lines.subList(0, limit)
            );
            shown.add("... ("
                    + (lines.size() - limit)
                    + " more lines)");
            return String.join("\n", shown);
        }

        return String.join("\n", lines);
    }
}
