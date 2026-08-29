package io.opendoggo;

import io.opendoggo.agent.AgentLoop;
import io.opendoggo.environment.Env;
import io.opendoggo.model.Message;
import io.opendoggo.model.ModelClient;
import io.opendoggo.model.impl.AnthropicClient;
import io.opendoggo.tool.ToolDispatch;
import io.opendoggo.tool.impl.ShellTool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ArrayNode;

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
        Env env = Env.load();

        String apiKey = env.get("ANTHROPIC_API_KEY");
        String modelId = env.get("MODEL_ID");
        String baseUrl = env.get("ANTHROPIC_BASE_URL");

        if (isBlank(apiKey) || isBlank(modelId)) {
            System.err.println(
                    "Missing configuration. Set "
                            + "ANTHROPIC_API_KEY and MODEL_ID "
                            + "in the environment or a .env file."
            );

            System.exit(1);
        }

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

        ToolDispatch toolDispatch = new ToolDispatch();
        toolDispatch.register(new ShellTool(workingDirectory));
        ArrayNode toolDefinitions = toolDispatch.toolDefinitions();
        ModelClient modelClient = new AnthropicClient(
                baseUrl,
                apiKey,
                modelId,
                systemPrompt,
                toolDefinitions
        );

        AgentLoop agentLoop = new AgentLoop(
                modelClient,
                toolDispatch
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
