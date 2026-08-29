package io.opendoggo.tool.impl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.opendoggo.tool.ToolHandler;

public class GlobTool implements ToolHandler {
    private static final int MAX_MATCHES = 150;
    private final Path workingDirectory;

    public GlobTool(Path workingDirectory) {
        this.workingDirectory =
                workingDirectory
                        .toAbsolutePath()
                        .normalize();
    }

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description() {
        return "Find files by glob pattern relative to "
                + "the workspace root, e.g. src/**/*.java "
                + "or *.md. Use this instead of running "
                + "find/ls via bash. Returns up to 150 "
                + "sorted paths.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema =
                JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");

        schema.putObject("properties")
                .putObject("pattern")
                .put("type", "string");

        schema.putArray("required").add("pattern");
        return schema;
    }

    @Override
    public String execute(JsonNode input){
         String pattern =
                input == null
                        ? null
                        : input.path("pattern").asText(null);
        if (pattern == null || pattern.isBlank()) {
            return "Error: pattern cannot be empty";
        }

        PathMatcher matcher;
        try {
            matcher = FileSystems
                    .getDefault()
                    .getPathMatcher("glob:" + pattern);
        } catch (IllegalArgumentException exception) {
            return "Error: Invalid pattern: " + pattern;
        }

        List<String> matches = new ArrayList<>();

        try (Stream<Path> stream =
                Files.walk(workingDirectory)) {

            stream.filter(Files::isRegularFile)
                    .map(workingDirectory::relativize)
                    .filter(matcher::matches)
                    .map(Path::toString)
                    .forEach(matches::add);

        } catch (IOException
                | UncheckedIOException exception) {
            // UncheckedIOException：流式遍历中途出错
            //（如目录无权限）会包在里面抛出。
            return "Error: " + exception.getMessage();
        }

        if (matches.isEmpty()) {
            return "(no matches)";
        }
        
        matches.sort(Comparator.naturalOrder());

        List<String> shown = matches.subList(
                0,
                Math.min(MAX_MATCHES, matches.size())
        );

        StringBuilder result = new StringBuilder(
                String.join("\n", shown)
        );

        if (matches.size() > MAX_MATCHES) {
            result.append(
                    "\n... (more matches omitted; "
                            + "narrow the pattern)"
            );
        }

        return result.toString();
    }
}

