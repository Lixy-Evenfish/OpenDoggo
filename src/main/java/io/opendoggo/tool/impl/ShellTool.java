package io.opendoggo.tool.impl;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.opendoggo.tool.ToolHandler;

/**
 * 在指定工作目录中执行 Shell 命令。
 *
 * s11：schema 增加 run_in_background 参数
 * （是否进后台由 BackgroundManager 判断，
 * 本工具只负责同步执行一条命令）；
 * run(command) 是可被后台线程复用的执行核心，
 * 返回输出与退出码；在跑进程登记进静态集合，
 * JVM 退出时统一终止（对应参考实现的
 * _shell_processes + atexit 生命周期清理）。
 */
public final class ShellTool implements ToolHandler {

    private static final int MAX_OUTPUT_LENGTH = 50000;

    /**
     * s11：当前在跑的全部命令进程（跨实例共享——
     * 父/子/后台命令都登记），对应参考实现的
     * 模块级 _shell_processes；JVM 正常退出时
     * 由 destroyAllRunning 统一停止。
     */
    private static final Set<Process> RUNNING_PROCESSES =
            ConcurrentHashMap.newKeySet();

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

        // s11：run_in_background 显式请求后台执行；
        // 非必填——缺省仍走同步路径。
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("command")
                .put("type", "string");
        properties.putObject("run_in_background")
                .put("type", "boolean");

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

        return run(command).output();
    }

    /**
     * s11：一次命令执行的产物——
     * output 是与 execute 一致的整理后输出；
     * exitCode 是进程退出码，超时/启动失败/中断时
     * 为 null（后台任务据此标记 failed）。
     */
    public record Outcome(String output, Integer exitCode) {
    }

    /**
     * s11：可复用的执行核心——同步与后台共用
     * 同一条路径（超时、输出上限、进程清理），
     * 对应参考实现BackgroundManager 复用的
     * _run_bash_process。
     */
    public Outcome run(String command) {
        Process process = null;
        try {

            process = new ProcessBuilder(
                    createShellCommand(command)
            )
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .start();

            RUNNING_PROCESSES.add(process);

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

                return new Outcome(
                        "Error: Timeout ("
                                + timeout.toSeconds()
                                + "s)",
                        null
                );
            }

            String output = new String(
                    outputFuture.join(),
                    Charset.defaultCharset()
            ).strip();

            if (output.isEmpty()) {
                output = "(no output)";
            }

            return new Outcome(
                    abbreviate(output, MAX_OUTPUT_LENGTH),
                    process.exitValue()
            );

        } catch (IOException exception) {
            return new Outcome(
                    "Error: " + exception.getMessage(),
                    null
            );

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new Outcome(
                    "Error: Command interrupted",
                    null
            );

        } catch (CompletionException exception) {
            return new Outcome(
                    "Error: Unable to read command output",
                    null
            );

        } finally {
            if (process != null) {
                RUNNING_PROCESSES.remove(process);

                if (process.isAlive()) {
                    destroyProcessTree(process);
                }
            }
        }
    }

    /**
     * s11：JVM 退出前停止全部在跑命令
     * （参考实现的 atexit/SIGTERM 生命周期清理——
     * 这是清理，不是沙箱）。
     */
    public static void destroyAllRunning() {
        for (Process process
                : new ArrayList<>(RUNNING_PROCESSES)) {
            destroyProcessTree(process);
        }
    }

    /** s11：当前在跑命令数（demo 等待进程登记用）。 */
    public static int runningCount() {
        return RUNNING_PROCESSES.size();
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
    private static void destroyProcessTree(
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
