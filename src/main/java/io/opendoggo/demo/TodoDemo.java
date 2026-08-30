package io.opendoggo.demo;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.opendoggo.agent.AgentLoop;
import io.opendoggo.hook.HookRunner;
import io.opendoggo.model.ContentBlock;
import io.opendoggo.model.Message;
import io.opendoggo.model.ModelClient;
import io.opendoggo.model.ModelResponse;
import io.opendoggo.tool.ToolDispatch;
import io.opendoggo.tool.ToolHandler;
import io.opendoggo.tool.impl.TodoWrite;

/**
 * s05 的自跑演示：不联网、不需要 API key。
 *
 * 用剧本模型（ScriptedModel）驱动真实的 AgentLoop、
 * 真实的 TodoWrite/TodoManager 和催更计数器，
 * 完整演出"计划 -> 干活三轮忘更新 -> reminder 注入 ->
 * 更新状态 -> 收尾"，末尾自带自检。
 *
 * 运行：
 *   mvn -q compile
 *   java -cp "target/classes:$(cat cp.txt)" io.opendoggo.demo.TodoDemo
 */
public final class TodoDemo {

    private TodoDemo() {
    }

    /** 剧本模型：每次调用按顺序吐出预设回复。 */
    private static final class ScriptedModel
            implements ModelClient {

        private final List<ModelResponse> script;
        private int next;

        private ScriptedModel(List<ModelResponse> script) {
            this.script = script;
        }

        @Override
        public ModelResponse createMessage(
                List<Message> messages
        ) {
            return script.get(next++);
        }
    }

    public static void main(String[] args) throws Exception {
        ToolDispatch dispatch = new ToolDispatch();
        dispatch.register(echoTool());
        dispatch.register(new TodoWrite());

        // 只挂演示需要的 hook：横幅 + 黄色任务清单展示。
        HookRunner hooks = new HookRunner();
        hooks.registerPreToolUse(toolCall -> {
            System.out.println(
                    "[HOOK-PreToolUse]> " + toolCall.name()
            );
            return null;
        });
        hooks.registerPostToolUse((toolCall, output) -> {
            if (!"todo_write".equals(toolCall.name())) {
                return;
            }

            if (output == null
                    || output.startsWith("Error:")) {
                return;
            }

            System.out.println();
            System.out.println(
                    "\u001B[33m## Current Tasks\u001B[0m"
            );
            System.out.println(output);
        });

        // 剧本：一个"会偷懒忘更新清单"的模型。
        ScriptedModel model = new ScriptedModel(List.of(
                // 第 1 轮：先列计划（3 步全 pending），计数归零。
                round(
                        "我先列个计划，三步。",
                        toolUse("t1", "todo_write", todos(
                                "Add type hints", "pending",
                                "Add docstrings", "pending",
                                "Add main guard", "pending"
                        ))
                ),
                // 第 2 轮：只干活没碰 todo，计数 1。
                round(
                        null,
                        echo("t2", "给 greet() 加类型标注")
                ),
                // 第 3 轮：计数 2。
                round(
                        null,
                        echo("t3", "补 docstring")
                ),
                // 第 4 轮：计数 3 —— 本轮结果里会被注入 reminder。
                round(
                        null,
                        echo("t4", "加 main guard")
                ),
                // 第 5 轮：模型"看到提醒"，更新任务状态，计数归零。
                round(
                        "收到提醒，更新一下任务状态。",
                        toolUse("t5", "todo_write", todos(
                                "Add type hints", "completed",
                                "Add docstrings", "completed",
                                "Add main guard", "in_progress"
                        ))
                ),
                // 第 6 轮：纯文本收尾，循环结束。
                round("三步全部完成，hello.py 重构结束。")
        ));

        List<Message> history = new ArrayList<>();
        history.add(
                Message.user("帮我重构 example/hello.py")
        );

        String answer = new AgentLoop(
                model,
                hooks,
                dispatch
        ).run(history);

        System.out.println();
        System.out.println("模型最终回复: " + answer);

        dumpHistory(history);
        selfCheck(history);
    }

    /** 一个无害的演示工具：原样回显 note。 */
    private static ToolHandler echoTool() {
        return new ToolHandler() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "Echo a note.";
            }

            @Override
            public JsonNode inputSchema() {
                ObjectNode schema =
                        JsonNodeFactory.instance.objectNode();
                schema.put("type", "object");
                return schema;
            }

            @Override
            public String execute(JsonNode input) {
                return "ok: "
                        + input.path("note").asText("");
            }
        };
    }

    /** 组一轮模型回复：一句话 + 若干工具调用。 */
    private static ModelResponse round(
            String saying,
            ContentBlock... calls
    ) {
        List<ContentBlock> blocks = new ArrayList<>();

        if (saying != null && !saying.isBlank()) {
            blocks.add(ContentBlock.text(saying));
        }

        blocks.addAll(List.of(calls));
        return new ModelResponse(blocks);
    }

    private static ContentBlock toolUse(
            String id,
            String name,
            ObjectNode input
    ) {
        return ContentBlock.toolUse(id, name, input);
    }

    private static ContentBlock echo(String id, String note) {
        ObjectNode input =
                JsonNodeFactory.instance.objectNode();
        input.put("note", note);
        return ContentBlock.toolUse(id, "echo", input);
    }

    /** content/status 成对传入，拼 todo_write 的输入。 */
    private static ObjectNode todos(String... pairs) {
        ObjectNode input =
                JsonNodeFactory.instance.objectNode();

        var array = input.putArray("todos");

        for (int i = 0; i < pairs.length; i += 2) {
            array.addObject()
                    .put("content", pairs[i])
                    .put("status", pairs[i + 1]);
        }

        return input;
    }

    /** 打印会话历史的线格式，能看到 reminder 搭在哪条消息里。 */
    private static void dumpHistory(List<Message> history) {
        System.out.println();
        System.out.println(
                "==== 会话历史（发给模型的线格式） ===="
        );

        for (Message message : history) {
            System.out.println(
                    "[" + message.role() + "] "
                            + message.content()
    );
        }
    }

    /** 自检：reminder 恰好注入一次。 */
    private static void selfCheck(List<Message> history) {
        long reminderCount = 0;

        for (Message message : history) {
            JsonNode content = message.content();

            if (content == null || !content.isArray()) {
                continue;
            }

            for (JsonNode block : content) {
                if ("text".equals(
                        block.path("type").asText()
                ) && block.path("text").asText()
                        .contains("<reminder>")) {
                    reminderCount++;
                }
            }
        }

        System.out.println();
        System.out.println(
                reminderCount == 1
                        ? "[PASS] 催更 reminder 恰好注入 1 次"
                        : "[FAIL] reminder 出现 " + reminderCount + " 次"
        );
    }
}
