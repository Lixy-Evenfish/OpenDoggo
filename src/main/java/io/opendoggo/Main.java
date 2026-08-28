package io.opendoggo;

import io.opendoggo.agent.AgentLoop;
import io.opendoggo.model.AnthropicClient;
import io.opendoggo.model.Message;
import io.opendoggo.model.ModelClient;
import io.opendoggo.tool.ShellTool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * OpenDoggo 入口，对应 s1 的 __main__ 段。
 *
 * 读取配置 -> 装配 AgentLoop -> 进入 REPL。
 */
public final class Main {

    private static final Set<String> EXIT_COMMANDS =
            Set.of("q", "exit", "");

    private Main() {
    }

    public static void main(String[] args) {
        Map<String, String> environment = loadEnvironment();

        String apiKey = environment.get("ANTHROPIC_API_KEY");
        String modelId = environment.get("MODEL_ID");

        if (isBlank(apiKey) || isBlank(modelId)) {
            System.err.println(
                    "Missing configuration. Set "
                            + "ANTHROPIC_API_KEY and MODEL_ID "
                            + "in the environment or a .env file."
            );

            System.exit(1);
        }

        String baseUrl = environment.get("ANTHROPIC_BASE_URL");

        if (isBlank(baseUrl)) {
            baseUrl = AnthropicClient.DEFAULT_BASE_URL;
        }

        Path workingDirectory =
                Path.of("").toAbsolutePath().normalize();

        // 与 s1 的 SYSTEM 一致，把工作目录写进提示词。
        String systemPrompt =
                "You are a coding agent at "
                        + workingDirectory
                        + ". Use bash to solve tasks. "
                        + "Act, don't explain.";

        ModelClient modelClient = new AnthropicClient(
                baseUrl,
                apiKey,
                modelId,
                systemPrompt
        );

        AgentLoop agentLoop = new AgentLoop(
                modelClient,
                new ShellTool(workingDirectory)
        );

        runRepl(agentLoop, workingDirectory);
    }

    /**
     * 读一行、跑一轮、打印最终回复。
     */
    private static void runRepl(
            AgentLoop agentLoop,
            Path workingDirectory
    ) {
        System.out.println("OpenDoggo s1: Agent Loop");
        System.out.println("cwd: " + workingDirectory);
        System.out.println(
                "Enter a question, press Enter to send. "
                        + "Type q to quit."
        );
        System.out.println();

        List<Message> history = new ArrayList<>();

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        System.in,
                        StandardCharsets.UTF_8
                )
        );

        while (true) {
            System.out.print("doggo >> ");
            System.out.flush();

            String query;

            try {
                query = reader.readLine();
            } catch (IOException exception) {
                break;
            }

            // Ctrl+D 或流结束。
            if (query == null) {
                break;
            }

            String normalized =
                    query.strip().toLowerCase(Locale.ROOT);

            if (EXIT_COMMANDS.contains(normalized)) {
                break;
            }

            // 失败时回滚到本轮之前，避免历史损坏。
            int checkpoint = history.size();

            history.add(Message.user(query));

            try {
                System.out.println(agentLoop.run(history));

            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                truncate(history, checkpoint);
                System.err.println("Interrupted.");
                break;

            } catch (IOException | RuntimeException exception) {
                truncate(history, checkpoint);
                System.err.println(
                        "Error: " + exception.getMessage()
                );
            }

            System.out.println();
        }
    }

    /**
     * 先读进程环境变量，再用 .env 覆盖。
     *
     * 与 s1 的 load_dotenv(override=True) 行为一致。
     */
    private static Map<String, String> loadEnvironment() {
        Map<String, String> values =
                new HashMap<>(System.getenv());

        Path envFile = Path.of(".env");

        if (Files.isRegularFile(envFile)) {
            try {
                for (String line : Files.readAllLines(
                        envFile,
                        StandardCharsets.UTF_8
                )) {
                    parseEnvLine(line, values);
                }
            } catch (IOException exception) {
                System.err.println(
                        "Warning: unable to read .env: "
                                + exception.getMessage()
                );
            }
        }

        return values;
    }

    private static void parseEnvLine(
            String line,
            Map<String, String> values
    ) {
        String trimmed = line.strip();

        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return;
        }

        int separator = trimmed.indexOf('=');

        if (separator <= 0) {
            return;
        }

        String key = trimmed.substring(0, separator).strip();
        String value =
                trimmed.substring(separator + 1).strip();

        // 去掉可选的包裹引号。
        if (value.length() >= 2
                && (value.startsWith("\"")
                        && value.endsWith("\"")
                || value.startsWith("'")
                        && value.endsWith("'"))) {

            value = value.substring(1, value.length() - 1);
        }

        values.put(key, value);
    }

    private static void truncate(
            List<Message> history,
            int size
    ) {
        while (history.size() > size) {
            history.remove(history.size() - 1);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
