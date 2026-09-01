package io.opendoggo.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * 代码沙箱：把模型生成的代码放进一次性 Docker 容器执行，
 * 在机制上隔离宿主——无网络（--network none）、根文件系统
 * 只读（--read-only）、只挂载本次运行的临时目录、非 root
 * 用户（-u 1000:1000）、内存/CPU/进程数限额、去权能
 * （--cap-drop ALL + no-new-privileges）。容器也不继承宿主
 * 环境变量（docker run 不透传，除非显式 -e）——
 * ANTHROPIC_API_KEY 天然进不了沙箱，零代码。
 *
 * 与三道闸门的分工：闸门是策略（用户审批拦危险操作），
 * 沙箱是机制（保证代码出不去）——隔离代替审批，
 * 所以 run_code 不注册权限规则。
 *
 * 双层超时：容器内 coreutils timeout（= 宿主上限 - 5s）
 * 先自杀，避免杀掉 docker CLI 后留下孤儿容器；
 * 宿主 waitFor + 进程树击杀兜底（ShellTool 同款）。
 */
public final class SandboxRunner {

    /** 宿主侧兜底超时（秒）；容器内自杀时限 = 此值 - 5。 */
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /** 单次输出进入上下文的上限；超限截断并全量落盘。 */
    private static final int MAX_OUTPUT_LENGTH = 20_000;

    /**
     * 语言白名单：镜像 + 代码文件名 + 容器内命令。
     * node/bash 复用本机已有的 node:24-slim；
     * python 需一次性 docker pull python:3.12-slim。
     */
    private record LanguageSpec(
            String image,
            String fileName,
            List<String> command
    ) {
    }

    private static final Map<String, LanguageSpec> LANGUAGES =
            createLanguages();

    private static Map<String, LanguageSpec> createLanguages() {
        Map<String, LanguageSpec> languages =
                new LinkedHashMap<>();

        languages.put(
                "python",
                new LanguageSpec(
                        "python:3.12-slim",
                        "main.py",
                        // -u 关闭输出缓冲：被超时击杀时
                        // 已打印的内容不丢。
                        List.of("python3", "-u", "main.py")
                )
        );
        languages.put(
                "node",
                new LanguageSpec(
                        "node:24-slim",
                        "main.js",
                        List.of("node", "main.js")
                )
        );
        languages.put(
                "bash",
                new LanguageSpec(
                        "node:24-slim",
                        "script.sh",
                        List.of("bash", "script.sh")
                )
        );

        return languages;
    }

    /** 每次执行的独立目录：staging 代码 + 全量输出落盘。 */
    private final Path runsRoot;

    private final int timeoutSeconds;

    public SandboxRunner(Path workingDirectory) {
        this(workingDirectory, DEFAULT_TIMEOUT_SECONDS);
    }

    /** timeoutSeconds 可调小，便于 demo 快速验证超时击杀。 */
    public SandboxRunner(
            Path workingDirectory,
            int timeoutSeconds
    ) {
        Objects.requireNonNull(
                workingDirectory,
                "workingDirectory cannot be null"
        );

        this.runsRoot = workingDirectory
                .toAbsolutePath()
                .normalize()
                .resolve(".sandbox")
                .resolve("runs");
        this.timeoutSeconds = timeoutSeconds;
    }

    /** 工具 schema 的语言枚举来源。 */
    public Set<String> languages() {
        return LANGUAGES.keySet();
    }

    /**
     * 执行一段代码并返回 tool_result 字符串。
     * 出错一律返回 "Error: ..."，不向循环抛异常。
     */
    public String run(String language, String code) {
        LanguageSpec spec = LANGUAGES.get(language);

        if (spec == null) {
            return "Error: unsupported language '"
                    + language + "'. Available: "
                    + String.join(", ", LANGUAGES.keySet());
        }

        if (code == null || code.isBlank()) {
            return "Error: code cannot be empty";
        }

        try {
            return execute(spec, code);

        } catch (IOException exception) {
            return "Error: " + exception.getMessage();

        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "Error: Command interrupted";

        } catch (CompletionException exception) {
            return "Error: Unable to read command output";
        }
    }

    private String execute(
            LanguageSpec spec,
            String code
    ) throws IOException, InterruptedException {
        String preflight = checkDocker(spec.image());

        if (preflight != null) {
            return preflight;
        }

        Path runDir = runsRoot.resolve(
                UUID.randomUUID().toString()
        );
        Files.createDirectories(runDir);
        Files.writeString(
                runDir.resolve(spec.fileName()),
                code,
                StandardCharsets.UTF_8
        );

        Process process = new ProcessBuilder(
                dockerCommand(runDir, spec)
        )
                .redirectErrorStream(true)
                .start();

        // 异步读取输出，避免缓冲区填满后容器进程卡死。
        CompletableFuture<byte[]> outputFuture =
                CompletableFuture.supplyAsync(
                        () -> readOutput(process)
                );

        boolean finished = process.waitFor(
                timeoutSeconds,
                TimeUnit.SECONDS
        );

        if (!finished) {
            destroyProcessTree(process);
            return "Error: Sandbox timeout ("
                    + timeoutSeconds + "s), process killed";
        }

        int exitCode = process.exitValue();
        String output = new String(
                outputFuture.join(),
                StandardCharsets.UTF_8
        ).strip();

        // 124 是容器内 timeout 的退出码：代码自身超时。
        if (exitCode == 124) {
            return "Error: Sandbox timeout (code ran"
                    + " longer than " + innerTimeout()
                    + "s), process killed";
        }

        if (output.isEmpty()) {
            output = "(no output)";
        }

        String result = "exit code: " + exitCode
                + "\n--- output ---\n";

        if (output.length() <= MAX_OUTPUT_LENGTH) {
            return result + output;
        }

        // 超限：全量落盘，上下文里只留截断 + 可找回路径
        // （与 s08 压缩管线的落盘口径一致）。
        Path fullOutput = runDir.resolve("output.txt");
        Files.writeString(
                fullOutput,
                output,
                StandardCharsets.UTF_8
        );

        return result
                + output.substring(0, MAX_OUTPUT_LENGTH)
                + "\n... (truncated)\nFull output: "
                + fullOutput;
    }

    /** 容器内自杀时限：比宿主兜底早 5 秒。 */
    private int innerTimeout() {
        return Math.max(1, timeoutSeconds - 5);
    }

    /**
     * 起容器前的预检。docker 不在 / 守护进程不可达 /
     * 镜像缺失各返回一句可行动的 Error（docker run 遇到
     * 缺失镜像会自动拉取，这里拦下来，避免回合中
     * 静默下载几十 MB）。返回 null 表示可以继续。
     */
    private String checkDocker(String image)
            throws IOException, InterruptedException {
        try {
            Process process = new ProcessBuilder(
                    "docker", "image", "inspect", image
            )
                    .redirectErrorStream(true)
                    .start();

            if (!process.waitFor(
                    10,
                    TimeUnit.SECONDS
            )) {
                process.destroyForcibly();
                return "Error: sandbox requires Docker"
                        + " (image inspect timed out)";
            }

            if (process.exitValue() == 0) {
                return null;
            }

            String message = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            );

            if (message.contains("Cannot connect")) {
                return "Error: sandbox requires Docker"
                        + " (daemon not reachable)";
            }

            return "Error: image " + image
                    + " not found locally. Run:"
                    + " docker pull " + image;

        } catch (IOException exception) {
            return "Error: sandbox requires Docker"
                    + " (docker not found)";
        }
    }

    /** 一次性容器的完整参数——机制隔离的全部所在。 */
    private List<String> dockerCommand(
            Path runDir,
            LanguageSpec spec
    ) {
        List<String> command = new ArrayList<>(List.of(
                "docker", "run", "--rm",
                "--network", "none",
                "--read-only",
                "--tmpfs", "/tmp:rw,noexec,nosuid,size=16m",
                "--volume", runDir + ":/sandbox:rw",
                "--workdir", "/sandbox",
                "--user", "1000:1000",
                "--memory", "256m",
                "--cpus", "1",
                "--pids-limit", "64",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                spec.image(),
                "timeout", String.valueOf(innerTimeout())
        ));

        command.addAll(spec.command());
        return command;
    }

    private static byte[] readOutput(Process process) {
        try {
            return process.getInputStream().readAllBytes();
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    private static void destroyProcessTree(Process process) {
        process.descendants()
                .forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
