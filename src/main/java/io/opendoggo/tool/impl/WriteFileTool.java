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
 * 把内容写入工作区内文件，父目录不存在时自动创建。
 */
public final class WriteFileTool implements ToolHandler {

    private final Path workingDirectory;

    public WriteFileTool(Path workingDirectory) {
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
        return "write_file";
    }

    @Override
    public String description() {
        return "Write content to a file.";
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
        properties.putObject("content")
                .put("type", "string");

        schema.putArray("required")
                .add("path")
                .add("content");
        return schema;
    }

    @Override
    public String execute(JsonNode input) {
        if (input == null || !input.has("content")) {
            return "Error: content is required";
        }

        String rawPath = input.path("path").asText(null);

        Path path = WorkspacePaths.resolveIn(
                workingDirectory,
                rawPath
        );

        if (path == null) {
            return "Error: path escapes workspace: "
                    + rawPath;
        }

        String content = input.path("content").asText();

        try {
            Files.createDirectories(path.getParent());
            Files.writeString(
                    path,
                    content,
                    StandardCharsets.UTF_8
            );

        } catch (IOException exception) {
            return "Error: " + exception.getMessage();
        }

        return "Wrote "
                + content.getBytes(StandardCharsets.UTF_8).length
                + " bytes to " + rawPath.strip();
    }
}
