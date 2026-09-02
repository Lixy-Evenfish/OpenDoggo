package io.opendoggo.demo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.opendoggo.agent.AgentLoop;
import io.opendoggo.background.BackgroundManager;
import io.opendoggo.hook.HookRunner;
import io.opendoggo.model.ContentBlock;
import io.opendoggo.model.Message;
import io.opendoggo.model.ModelClient;
import io.opendoggo.model.ModelResponse;
import io.opendoggo.permission.PermissionChecker;
import io.opendoggo.tool.ToolDispatch;
import io.opendoggo.tool.impl.ShellTool;

/**
 * s11 验收 demo（无需 API key）：脚本 ModelClient
 * 驱动真实 AgentLoop + 真实 ShellTool，验证——
 * bash schema 的 run_in_background 参数、显式判定
 * （只有 bash + 布尔 true）、占位 tool_result 与
 * bg_id、后续轮次的 task_notification 通知收集
 * （另起 user 消息 / 搭工具结果批次两种并入方式、
 * 不复用 tool_use_id）、failed 状态与退出码前缀、
 * 空命令/非 bash 守卫、权限先于后台、collect 单次
 * 消费、JVM 退出清理（destroyAllRunning）。
 */
public final class BackgroundDemo {

    private static final ObjectMapper MAPPER =
            new ObjectMapper();

    private static final String PLACEHOLDER_PREFIX =
            "[Background task ";

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path base = Files.createTempDirectory("bg-demo");

        schemaCheck();
        requestCheck();
        syncUnaffectedCheck(base);
        startAndCollectCheck(base);
        toolResultsTailCheck(base);
        guardChecks(base);
        failedStatusCheck(base);
        permissionCheck(base);
        destroyRunningCheck(base);

        System.out.println();
        System.out.println(
                "BackgroundDemo: " + passed + " passed, "
                        + failed + " failed"
        );

        if (failed > 0) {
            System.exit(1);
        }
    }

    /** bash schema 增加 run_in_background（布尔、非必填）。 */
    private static void schemaCheck() {
        System.out.println(
                "== 1) bash schema: run_in_background 参数 =="
        );

        JsonNode schema = new ShellTool(
                Path.of(".")
        ).inputSchema();

        JsonNode flag = schema.path("properties")
                .path("run_in_background");

        check(flag.isObject() && "boolean".equals(
                        flag.path("type").asText()),
                "run_in_background 是布尔参数");

        check(schema.path("required").toString()
                        .equals("[\"command\"]"),
                "required 仍只有 command");
    }

    /** should_run_background：只有 bash + 布尔 true。 */
    private static void requestCheck() {
        System.out.println(
                "== 2) isBackgroundRequest: 显式判定 =="
        );

        BackgroundManager manager = bareManager();

        check(manager.isBackgroundRequest(
                        "bash", json(
                                "{\"command\": \"x\", "
                                        + "\"run_in_background\": true}"
                        )),
                "bash + true 进后台");

        check(!manager.isBackgroundRequest(
                        "bash", json(
                                "{\"command\": \"x\", "
                                        + "\"run_in_background\": false}"
                        )),
                "bash + false 不进后台");

        check(!manager.isBackgroundRequest(
                        "bash", json("{\"command\": \"x\"}")),
                "参数缺省不进后台");

        check(!manager.isBackgroundRequest(
                        "read_file", json(
                                "{\"path\": \"a\", "
                                        + "\"run_in_background\": true}"
                        )),
                "非 bash 工具不进后台");

        check(!manager.isBackgroundRequest(
                        "bash", json(
                                "{\"command\": \"x\", "
                                        + "\"run_in_background\": \"true\"}"
                        )),
                "字符串 \"true\" 不算显式请求");
    }

    /** 同步路径不受影响：不带参数照常执行。 */
    private static void syncUnaffectedCheck(Path base) {
        System.out.println(
                "== 3) 同步路径: 不带参数照常执行 =="
        );

        ToolDispatch dispatch = dispatchWithShell(base);

        String output = dispatch.execute(
                ContentBlock.toolUse(
                        "s1",
                        "bash",
                        json("{\"command\": \"echo sync-ok\"}")
                )
        );

        check("sync-ok".equals(output),
                "同步 bash 返回原始输出");
    }

    /**
     * 启动 + 收集（方式一）：占位 tool_result 携带
     * bg_id，PostToolUse 见到占位串；上轮以
     * assistant 文本收尾时，通知另起一条 user 消息。
     */
    private static void startAndCollectCheck(Path base)
            throws Exception {
        System.out.println(
                "== 4) 循环内启动 + 下一轮收集 =="
        );

        Fixture fixture = new Fixture(base);
        List<String> postOutputs = new ArrayList<>();
        fixture.hooks.registerPostToolUse(
                (toolCall, output) ->
                        postOutputs.add(String.valueOf(output))
        );

        // 第一轮：显式请求后台（sleep 让任务活过本轮）。
        fixture.responses.add(response(
                toolUseBlock("b1",
                        "{\"command\": \"sleep 0.3 "
                                + "&& echo bg-demo\", "
                                + "\"run_in_background\": true}"),
                textBlock("好的，稍后再看结果。")
        ));
        fixture.responses.add(response(
                textBlock("done")
        ));

        String answer = fixture.loop().run(
                fixture.history, "帮我后台跑个命令"
        );

        check("done".equals(answer),
                "循环在占位结果后继续并正常结束");

        JsonNode resultBlock = fixture.history.get(2)
                .content().get(0);

        check("tool_result".equals(
                        resultBlock.path("type").asText()),
                "占位结果仍是 tool_result（配对完整）");

        check(resultBlock.path("content").asText()
                        .equals(PLACEHOLDER_PREFIX
                                + "bg_0001 started] The result "
                                + "will be collected on a "
                                + "later turn."),
                "占位内容带 bg_id 与后续收集说明");

        check(postOutputs.contains(PLACEHOLDER_PREFIX
                        + "bg_0001 started] The result "
                        + "will be collected on a "
                        + "later turn."),
                "PostToolUse 见到占位输出");

        check(awaitStatus(fixture.manager, "bg_0001",
                        "completed"),
                "后台任务真实执行到 completed");

        // 第二轮：此刻上轮以 assistant 收尾，
        // 通知应另起一条 user 消息注入。
        fixture.responses.add(response(textBlock("ok")));

        String answer2 = fixture.loop().run(
                fixture.history, "继续"
        );

        check("ok".equals(answer2),
                "第二次运行正常结束");

        Message injected = fixture.history.get(4);

        check("user".equals(injected.role())
                        && injected.content().isArray()
                        && injected.content().size() == 1,
                "通知另起一条单块 user 消息");

        checkNotification(injected.content().get(0)
                .path("text").asText());

        // 模型在本轮请求里已经看到通知。
        List<Message> seen =
                fixture.model.received().get(2);

        check(seen.size() == 5
                        && seen.get(4).content().get(0)
                        .path("text").asText()
                        .contains("<task_notification>"),
                "模型请求携带刚注入的通知");
    }

    /**
     * 收集方式二：上一批工具结果之后完成时，
     * 通知以 text 块搭进同一条 user 消息
     * （README 的 Turn-3 形态），不占用 tool_use_id。
     */
    private static void toolResultsTailCheck(Path base)
            throws Exception {
        System.out.println(
                "== 5) 通知注入: 搭工具结果批次 =="
        );

        Fixture fixture = new Fixture(base);

        fixture.responses.add(response(
                toolUseBlock("c1",
                        "{\"command\": \"sleep 0.4 "
                                + "&& echo slow-done\", "
                                + "\"run_in_background\": true}")
        ));
        // 模型调用本身耗时（真实网关约 20s 的替身）：
        // 等后台任务完成后再返回同步调用，
        // 下一轮收集时最后一条恰是工具结果消息。
        fixture.gates.put(1, () -> "completed".equals(
                fixture.manager.statusOf("bg_0001")));
        fixture.responses.add(response(
                toolUseBlock("c2",
                        "{\"command\": \"echo other-work\"}")
        ));
        fixture.responses.add(response(
                textBlock("全部完成")
        ));

        String answer = fixture.loop().run(
                fixture.history, "跑个慢命令再干别的"
        );

        check("全部完成".equals(answer),
                "三轮循环正常结束");

        JsonNode batch = fixture.history.get(4).content();

        check(batch.size() == 2
                        && "tool_result".equals(
                        batch.get(0).path("type").asText())
                        && "other-work".equals(
                        batch.get(0).path("content").asText()),
                "同步结果照常是 tool_result");

        String notification = batch.get(1)
                .path("text").asText();

        check("text".equals(
                        batch.get(1).path("type").asText())
                        && notification.contains(
                        "<task_notification>"),
                "通知以独立 text 块并入同一 user 消息");

        checkNotification(notification);
    }

    /** 空命令与非 bash 守卫（参考实现的 ValueError）。 */
    private static void guardChecks(Path base)
            throws Exception {
        System.out.println(
                "== 6) 守卫: 空命令 / 非 bash =="
        );

        Fixture fixture = new Fixture(base);

        fixture.responses.add(response(
                toolUseBlock("g1",
                        "{\"command\": \"\", "
                                + "\"run_in_background\": true}"),
                textBlock("好的")
        ));
        fixture.responses.add(response(textBlock("ok")));

        fixture.loop().run(fixture.history, "空命令");

        String content = fixture.history.get(2)
                .content().get(0).path("content").asText();

        check("Error: Bash command cannot be empty"
                        .equals(content),
                "空命令回 Error 工具结果，不启动任务");

        BackgroundManager manager = bareManager();

        String message = thrownMessage(
                () -> manager.start(
                        "read_file",
                        "g2",
                        json("{\"path\": \"x\"}")
                )
        );

        check("Only Bash commands can run in the background"
                        .equals(message),
                "非 bash 调用被 start 守卫拒绝");
    }

    /** 非零退出 -> failed + 退出码前缀；collect 只消费一次。 */
    private static void failedStatusCheck(Path base)
            throws Exception {
        System.out.println(
                "== 7) failed 状态 + 通知格式 + 单次消费 =="
        );

        BackgroundManager manager =
                new BackgroundManager(shell(base));

        manager.start(
                "bash", "f1", json(
                        "{\"command\": \"exit 3\"}"
                )
        );

        check(awaitStatus(manager, "bg_0001", "failed"),
                "非零退出标记 failed");

        List<String> notifications = manager.collect();

        check(notifications.size() == 1,
                "完成队列交付一条通知");

        String notification = notifications.get(0);

        check(notification.contains(
                        "<status>failed</status>"),
                "通知带 failed 状态");

        check(notification.contains("<summary>Error: "
                        + "command exited with status 3"),
                "failed 摘要带退出码前缀");

        check(manager.collect().isEmpty(),
                "collect 之后完成队列已空（单次消费）");
    }

    /** 权限先于后台：deny list 命中的后台调用不启动。 */
    private static void permissionCheck(Path base)
            throws Exception {
        System.out.println(
                "== 8) 权限先于后台: deny list 拦截 =="
        );

        Fixture fixture = new Fixture(base);
        PermissionChecker checker = new PermissionChecker(
                base,
                (name, input, reason) -> false,
                name -> true
        );
        fixture.hooks.registerPreToolUse(checker::check);
        List<String> postOutputs = new ArrayList<>();
        fixture.hooks.registerPostToolUse(
                (toolCall, output) -> postOutputs.add(output)
        );

        fixture.responses.add(response(
                toolUseBlock("p1",
                        "{\"command\": \"sudo apt install x\", "
                                + "\"run_in_background\": true}"),
                textBlock("收到")
        ));
        fixture.responses.add(response(textBlock("ok")));

        fixture.loop().run(fixture.history, "后台装包");

        String content = fixture.history.get(2)
                .content().get(0).path("content").asText();

        check("Permission denied by deny list"
                        .equals(content),
                "后台调用先过权限，命中硬拒绝表");

        check(fixture.manager.statusOf("bg_0001") == null,
                "被拒绝的调用没有进入后台");

        check(postOutputs.equals(List.of(
                        "Permission denied by deny list")),
                "被拒绝的调用仍产生 PostToolUse 结果通知");
    }

    /** JVM 退出清理：destroyAllRunning 终止在跑后台命令。 */
    private static void destroyRunningCheck(Path base)
            throws Exception {
        System.out.println(
                "== 9) 生命周期: destroyAllRunning 清理 =="
        );

        BackgroundManager manager =
                new BackgroundManager(shell(base));

        manager.start(
                "bash", "d1",
                json("{\"command\": \"sleep 30\"}")
        );

        // 等 worker 真正把进程登记进静态集合再清理，
        // 否则 destroyAllRunning 可能扑空（竞态）。
        long deadline =
                System.currentTimeMillis() + 10_000;

        while (ShellTool.runningCount() == 0
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        ShellTool.destroyAllRunning();

        check(awaitStatus(manager, "bg_0001", "failed"),
                "被清理的任务以 failed 收场");

        String notification = manager.collect().get(0);

        check(notification.contains("exited with status"),
                "清理摘要带退出码前缀");
    }

    // ---- 公共脚手架 ----

    /** 一个场景专属的循环 + 管理器 + 脚本模型。 */
    private static final class Fixture {
        final BackgroundManager manager;
        final ToolDispatch dispatch;
        final HookRunner hooks;
        final ScriptedModel model;
        final List<ModelResponse> responses = new ArrayList<>();
        final Map<Integer, BooleanSupplier> gates =
                new java.util.LinkedHashMap<>();
        final List<Message> history = new ArrayList<>();

        Fixture(Path base) {
            manager = new BackgroundManager(shell(base));
            dispatch = dispatchWithShell(base);
            hooks = new HookRunner();
            model = new ScriptedModel(responses, gates);
            history.add(Message.user("场景查询"));
        }

        AgentLoop loop() {
            return new AgentLoop(
                    model,
                    hooks,
                    dispatch,
                    null,
                    manager
            );
        }
    }

    /** 脚本模型：按序吐出预设回复，按下标可先等闸门。 */
    private static final class ScriptedModel
            implements ModelClient {

        private final List<ModelResponse> responses;
        private final Map<Integer, BooleanSupplier> gates;
        private final List<List<Message>> received =
                new ArrayList<>();

        private int index;

        private ScriptedModel(
                List<ModelResponse> responses,
                Map<Integer, BooleanSupplier> gates
        ) {
            this.responses = responses;
            this.gates = gates;
        }

        List<List<Message>> received() {
            return received;
        }

        @Override
        public ModelResponse createMessage(
                List<Message> messages
        ) {
            received.add(messages);

            BooleanSupplier gate =
                    gates.get(index);

            if (gate != null) {
                long deadline =
                        System.currentTimeMillis() + 10_000;

                while (!gate.getAsBoolean()
                        && System.currentTimeMillis()
                        < deadline) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            return responses.get(index++);
        }
    }

    private static BackgroundManager bareManager() {
        return new BackgroundManager(new ShellTool(
                Path.of(System.getProperty("java.io.tmpdir"))
        ));
    }

    private static ShellTool shell(Path base) {
        return new ShellTool(base);
    }

    private static ToolDispatch dispatchWithShell(Path base) {
        ToolDispatch dispatch = new ToolDispatch();
        dispatch.register(shell(base));
        return dispatch;
    }

    private static ModelResponse response(
            ContentBlock... blocks
    ) {
        return new ModelResponse(List.of(blocks));
    }

    private static ContentBlock toolUseBlock(
            String id,
            String inputJson
    ) {
        return ContentBlock.toolUse(
                id, "bash", json(inputJson)
        );
    }

    private static ContentBlock textBlock(String text) {
        return ContentBlock.text(text);
    }

    /** 通知格式与内容断言（completed 场景共用）。 */
    private static void checkNotification(String text) {
        check(text.contains("<task_id>bg_0001</task_id>"),
                "通知带任务编号");

        check(text.contains("<status>completed</status>"),
                "通知带完成状态");

        check(text.contains("<command>"),
                "通知带原命令");

        check(text.contains("bg-demo")
                        || text.contains("slow-done"),
                "摘要携带命令输出");
    }

    /** 轮询等状态，最多 10 秒；返回是否等到。 */
    private static boolean awaitStatus(
            BackgroundManager manager,
            String taskId,
            String expected
    ) throws InterruptedException {
        long deadline =
                System.currentTimeMillis() + 10_000;

        while (!expected.equals(manager.statusOf(taskId))
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        return expected.equals(manager.statusOf(taskId));
    }

    private static String thrownMessage(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            return exception.getMessage();
        }

        return null;
    }

    private static JsonNode json(String literal) {
        try {
            return MAPPER.readTree(literal);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void check(
            boolean condition,
            String label
    ) {
        if (condition) {
            passed++;
        } else {
            failed++;
        }

        System.out.println(
                (condition ? "PASS" : "FAIL")
                        + " - " + label
        );
    }
}
