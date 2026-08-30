package io.opendoggo.tool.impl;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.opendoggo.tool.ToolHandler;

/**
 * 在指定工作目录中执行 Shell 命令。
 */
public final class ShellTool implements ToolHandler {

    private static final int MAX_OUTPUT_LENGTH = 50000;

    private final Path workingDirectory;
    private final Duration timeout;

    public ShellTool(Path workingDirectory) {
        Objects.requireNonNull(
                workingDirectory,
                "workingDirectory cannot be null"
        );

        this.workingDirectory =
                workingDirectory
                        .toAbsolutePath()
                        .normalize();

        this.timeout = Duration.ofSeconds(120);
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "Run a shell command.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema =
                JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");

        schema.putObject("properties")
                .putObject("command")
                .put("type", "string");

        schema.putArray("required").add("command");
        return schema;
    }
    /**
     * 执行命令并返回标准输出和错误输出。
     */

    @Override
    public String execute(JsonNode input) {
        JsonNode node = input.get("command");
        String command = (node == null) ? null : node.asText();
        if (command == null || command.isBlank()) {
            return "Error: command cannot be empty";
        }

        Process process = null;
        try {
            
            process = new ProcessBuilder(
                    createShellCommand(command)
            )
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .start();

            Process runningProcess = process;

            // 异步读取输出，避免输出缓冲区填满后进程卡死。
            CompletableFuture<byte[]> outputFuture =
                    CompletableFuture.supplyAsync(() -> {
                        try {
                            return runningProcess
                                    .getInputStream()
                                    .readAllBytes();
                        } catch (IOException exception) {
                            throw new CompletionException(
                                    exception
                            );
                        }
                    });

            boolean finished = process.waitFor(
                    timeout.toMillis(),
                    TimeUnit.MILLISECONDS
            );

            if (!finished) {
                destroyProcessTree(process);

                return "Error: Timeout ("
                        + timeout.toSeconds()
                        + "s)";
            }

            String output = new String(
                    outputFuture.join(),
                    Charset.defaultCharset()
            ).strip();

            if (output.isEmpty()) {
                return "(no output)";
            }

            return abbreviate(
                    output,
                    MAX_OUTPUT_LENGTH
            );

        } catch (IOException exception) {
            return "Error: " + exception.getMessage();

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "Error: Command interrupted";

        } catch (CompletionException exception) {
            return "Error: Unable to read command output";

        } finally {
            if (process != null && process.isAlive()) {
                destroyProcessTree(process);
            }
        }
    }

    /**
     * WSL/Linux 使用 sh，Windows 使用 cmd。
     */
    private List<String> createShellCommand(
            String command
    ) {
        String operatingSystem =
                System.getProperty("os.name")
                        .toLowerCase(Locale.ROOT);

        if (operatingSystem.contains("windows")) {
            return List.of(
                    "cmd.exe",
                    "/d",
                    "/s",
                    "/c",
                    command
            );
        }

        return List.of(
                "/bin/sh",
                "-lc",
                command
        );
    }

    /**
     * 超时或退出时终止命令及其子进程。
     */
    private void destroyProcessTree(
            Process process
    ) {
        process.descendants()
                .forEach(
                        ProcessHandle::destroyForcibly
                );

        process.destroyForcibly();
    }

    private String abbreviate(
            String value,
            int maximumLength
    ) {
        if (value.length() <= maximumLength) {
            return value;
        }

        return value.substring(
                0,
                maximumLength
        );
    }
}