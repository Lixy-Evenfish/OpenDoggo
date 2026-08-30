package io.opendoggo;

import io.opendoggo.agent.AgentLoop;
import io.opendoggo.environment.Env;
import io.opendoggo.hook.HookRunner;
import io.opendoggo.model.Message;
import io.opendoggo.model.ModelClient;
import io.opendoggo.model.impl.AnthropicClient;
import io.opendoggo.permission.ConsoleApprovalPrompt;
import io.opendoggo.permission.PermissionChecker;
import io.opendoggo.tool.ToolDispatch;
import io.opendoggo.tool.impl.EditFileTool;
import io.opendoggo.tool.impl.GlobTool;
import io.opendoggo.tool.impl.ReadFileTool;
import io.opendoggo.tool.impl.ShellTool;
import io.opendoggo.tool.impl.TodoWrite;
import io.opendoggo.tool.impl.WriteFileTool;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
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

        // 与 s2 的 SYSTEM 一致，把工作目录写进提示词；
        // s03 增加破坏性操作需审批的声明；
        // s05 增加先计划再执行的引导。
        String systemPrompt =
                "You are a coding agent at "
                        + workingDirectory
                        + ". Use the available tools "
                        + "to solve tasks. "
                        + "Act, don't explain. "
                        + "Before starting any "
                        + "multi-step task, use "
                        + "todo_write to plan your "
                        + "steps. Update status as "
                        + "you go. "
                        + "All destructive operations "
                        + "require user approval.";

        ToolDispatch toolDispatch = initTools(workingDirectory);
        ArrayNode toolDefinitions = toolDispatch.toolDefinitions();
        ModelClient modelClient = new AnthropicClient(
                baseUrl,
                apiKey,
                modelId,
                systemPrompt,
                toolDefinitions
        );

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        System.in,
                        StandardCharsets.UTF_8
                )
        );

        // 闸门 3 的控制台实现：默认拒绝。
        PermissionChecker permissionChecker =
                new PermissionChecker(
                        workingDirectory,
                        new ConsoleApprovalPrompt(reader)
                );

        HookRunner hookRunner = initHooks(
                permissionChecker,
                workingDirectory
        );

        AgentLoop agentLoop = new AgentLoop(
                modelClient,
                hookRunner,
                toolDispatch
        );

        runRepl(agentLoop, hookRunner, workingDirectory, reader);
    }

    /**
     * 工具装配：创建注册表并登记全部工具。
     * 新增工具 = 实现 ToolHandler 后在这里登记一行，
     * tools 数组由注册表自动生成。
     */
    private static ToolDispatch initTools(Path workingDirectory) {
        ToolDispatch toolDispatch = new ToolDispatch();
        toolDispatch.register(new ShellTool(workingDirectory));
        toolDispatch.register(new ReadFileTool(workingDirectory));
        toolDispatch.register(new WriteFileTool(workingDirectory));
        toolDispatch.register(new EditFileTool(workingDirectory));
        toolDispatch.register(new GlobTool(workingDirectory));
        toolDispatch.register(new TodoWrite()); 
        return toolDispatch;
    }

    /**
     * s04 的 hook 装配：创建注册表并按 agent 周期
     * （输入 → 执行前 → 执行后 → 退出）注册全部 hook。
     * 同一事件内注册顺序即执行顺序：
     * 权限排在横幅与日志之前——被拒绝的调用什么都不打。
     */
    private static HookRunner initHooks(
            PermissionChecker permissionChecker,
            Path workingDirectory
    ) {
        HookRunner hookRunner = new HookRunner();

        // UserPromptSubmit：输入上下文提示（context_inject_hook）。
        hookRunner.registerUserPromptSubmit(
                query -> System.out.println(
                        "[HOOK-UserPromptSubmit] UserPromptSubmit: working in "
                                + workingDirectory
                )
        );

        // PreToolUse 1/3：三道闸门（s03 权限逻辑整体搬上 hook）。
        hookRunner.registerPreToolUse(
                permissionChecker::check
        );

        // PreToolUse 2/3：工具横幅（原 AgentLoop 里的 "> name"）。
        // 注册在权限之后——被拒绝的调用不打横幅。
        hookRunner.registerPreToolUse(toolCall -> {
            System.out.println("[HOOK-PreToolUse]> " + toolCall.name());
            return null;
        });

        // PreToolUse 3/3：调用日志（log_hook）——
        // 取前两个参数值、截断 60 字符；仅旁观。
        hookRunner.registerPreToolUse(toolCall -> {
            List<String> values = new ArrayList<>();
            JsonNode input = toolCall.input();

            if (input != null) {
                input.forEach(
                        node -> values.add(node.asText())
                );
            }

            String argsPreview =
                    values.stream().limit(2).toList().toString();

            if (argsPreview.length() > 60) {
                argsPreview =
                        argsPreview.substring(0, 60);
            }

            System.out.println(
                    "[HOOK] " + toolCall.name()
                            + "(" + argsPreview + ")"
            );
            return null;
        });

        // PostToolUse 1/3：控制台全量打印工具输出（调试用），
        // 完整输出同时回传给模型。
        hookRunner.registerPostToolUse((toolCall, output) -> {
            // s05：todo_write 有专门的展示块，不重复打印。
            if ("todo_write".equals(toolCall.name())) {
                return;
            }

            System.out.println("[HOOK-PostToolUse]本地工具调用返回结果（to LLM）————————");
            System.out.println(output);
            System.out.println("END———————————————————————————————————");
        });

        // PostToolUse 2/3：大输出警告（large_output_hook）。
        // ShellTool 输出上限 5 万字符，实际由 read_file 等触发。
        hookRunner.registerPostToolUse((toolCall, output) -> {
            if (output != null && output.length() > 100_000) {
                System.out.println(
                        "[HOOK] Large output from "
                                + toolCall.name()
                                + ": " + output.length()
                                + " chars"
                );
            }
        });

        // PostToolUse 3/3（s05）：todo_write 的控制台展示——
        // 参考实现在工具里 print 黄色 "## Current Tasks"，
        // 按 UI-free 原则搬到 hook 层。
        hookRunner.registerPostToolUse((toolCall, output) -> {
            if (!"todo_write".equals(toolCall.name())) {
                return;
            }

            // 参考实现校验失败时直接返回，不打展示块。
            if (output == null || output.startsWith("Error:")) {
                return;
            }

            System.out.println();
            System.out.println("\u001B[33m## Current Tasks\u001B[0m");
            System.out.println(output);
        });

        // Stop：会话统计（summary_hook）——
        // 数历史里的 tool_result 块；返回 null 允许退出。
        hookRunner.registerStop(messages -> {
            long toolCount = 0;

            for (Message message : messages) {
                JsonNode content = message.content();

                if (content != null && content.isArray()) {
                    for (JsonNode block : content) {
                        if ("tool_result".equals(
                                block.path("type").asText()
                        )) {
                            toolCount++;
                        }
                    }
                }
            }

            System.out.println(
                    "[HOOK] Stop: session used "
                            + toolCount + " tool calls"
            );
            return null;
        });

        return hookRunner;
    }

    /**
     * 读一行、跑一轮、打印最终回复。
     */
    private static void runRepl(
            AgentLoop agentLoop,
            HookRunner hookRunner,
            Path workingDirectory,
            BufferedReader reader
    ) {
        System.out.println("OpenDoggo v2: Agent Loop & Hook");
        System.out.println("cwd: " + workingDirectory);
        System.out.println(
                "Enter a question, press Enter to send. "
                        + "Type q to quit."
        );
        System.out.println();

        List<Message> history = new ArrayList<>();

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

            // s04：query 进入历史、见到 LLM 之前，
            // 通知 UserPromptSubmit hook。
            hookRunner.triggerUserPromptSubmit(query);

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
