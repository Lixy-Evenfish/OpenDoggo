package io.opendoggo.background;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

import io.opendoggo.tool.impl.ShellTool;

/**
 * s11：后台任务管理器——慢 bash 命令进后台线程，
 * 当前工具调用立即返回 bg_id 占位结果，Agent Loop
 * 继续运行；后续轮次由循环在模型调用前收集已完成
 * 的结果，以 task_notification 通知加入对话。
 *
 * 对应参考实现的 BackgroundManager：
 * tasks 登记在跑任务、results 存完成产物、
 * ready 是完成队列；worker 是 daemon 线程，
 * 任务以非零退出码或 worker 异常结束时标记 failed。
 * 通知不复用原 tool_use_id——原 tool call 已经
 * 用占位 tool_result 回复过，一个 tool_use 仍然
 * 只对应一个 tool_result。
 *
 * 线程模型：start/collect 由循环线程调用，
 * worker 在后台线程执行；tasks/results/ready/counter
 * 全部在锁内读写。命令执行复用 ShellTool.run
 * （同步与后台共用同一条路径），进程的
 * JVM 退出清理由 ShellTool.destroyAllRunning 负责。
 */
public final class BackgroundManager {

    private static final String BASH_TOOL = "bash";

    /** bg_0001 风格的任务编号。 */
    private static final String ID_FORMAT = "bg_%04d";

    private static final int SUMMARY_PREVIEW_LENGTH = 500;

    /** 一条后台任务的登记项（完成时以新实例整体替换）。 */
    private record Task(
            String toolUseId,
            String command,
            String status
    ) {
    }

    private final ShellTool shellTool;

    private final Map<String, Task> tasks =
            new LinkedHashMap<>();

    private final Map<String, String> results =
            new HashMap<>();

    private final List<String> ready = new ArrayList<>();

    private int counter = 0;

    private final Object lock = new Object();

    public BackgroundManager(ShellTool shellTool) {
        this.shellTool = Objects.requireNonNull(
                shellTool,
                "shellTool cannot be null"
        );
    }

    /**
     * 参考实现的 should_run_background：只有 bash
     * 且 run_in_background 明确为布尔 true 才进后台
     * ——不按 install/build 等关键词猜测。
     */
    public boolean isBackgroundRequest(
            String toolName,
            JsonNode toolInput
    ) {
        if (!BASH_TOOL.equals(toolName)
                || toolInput == null) {
            return false;
        }

        JsonNode flag =
                toolInput.get("run_in_background");

        return flag != null
                && flag.isBoolean()
                && flag.booleanValue();
    }

    /**
     * 登记任务并启动 daemon 线程，立即返回 bg_id。
     * 只接受 bash 调用；空命令拒绝。
     */
    public String start(
            String toolName,
            String toolUseId,
            JsonNode toolInput
    ) {
        if (!BASH_TOOL.equals(toolName)) {
            throw new IllegalArgumentException(
                    "Only Bash commands can run "
                            + "in the background"
            );
        }

        JsonNode node =
                toolInput == null
                        ? null
                        : toolInput.get("command");

        String command =
                node == null ? null : node.asText();

        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException(
                    "Bash command cannot be empty"
            );
        }

        String taskId;

        synchronized (lock) {
            counter++;
            taskId = String.format(ID_FORMAT, counter);
            tasks.put(
                    taskId,
                    new Task(toolUseId, command, "running")
            );
        }

        Thread worker = new Thread(
                () -> run(taskId, command),
                "background-" + taskId
        );
        worker.setDaemon(true);

        try {
            worker.start();
        } catch (RuntimeException exception) {
            // 线程起不来就把登记撤回，不留僵尸任务。
            synchronized (lock) {
                tasks.remove(taskId);
            }
            throw exception;
        }

        return taskId;
    }

    /**
     * worker：执行命令、格式化结果、标记完成状态，
     * 进入完成队列等待下一次收集。
     */
    private void run(String taskId, String command) {
        String result;
        String status;

        try {
            ShellTool.Outcome outcome =
                    shellTool.run(command);

            result = formatResult(outcome);
            status = outcome.exitCode() != null
                    && outcome.exitCode() == 0
                    ? "completed"
                    : "failed";

        } catch (RuntimeException exception) {
            result = "Error: "
                    + exception.getMessage();
            status = "failed";
        }

        synchronized (lock) {
            Task task = tasks.get(taskId);

            if (task == null) {
                return;
            }

            tasks.put(
                    taskId,
                    new Task(
                            task.toolUseId(),
                            task.command(),
                            status
                    )
            );

            results.put(taskId, result);
            ready.add(taskId);
        }
    }

    /**
     * 取走全部已完成任务，格式化为 task_notification
     * 通知列表；没有已完成任务时返回空列表。
     * 每条通知只被收集一次。
     */
    public List<String> collect() {
        List<String> collected;

        synchronized (lock) {
            collected = new ArrayList<>(ready);
            ready.clear();
        }

        List<String> notifications = new ArrayList<>();

        for (String taskId : collected) {
            Task task;
            String result;

            synchronized (lock) {
                task = tasks.remove(taskId);
                result = results.remove(taskId);
            }

            if (task == null) {
                continue;
            }

            notifications.add(
                    "<task_notification>\n"
                            + "  <task_id>"
                            + taskId + "</task_id>\n"
                            + "  <status>"
                            + task.status() + "</status>\n"
                            + "  <command>"
                            + task.command() + "</command>\n"
                            + "  <summary>"
                            + preview(result,
                            SUMMARY_PREVIEW_LENGTH)
                            + "</summary>\n"
                            + "</task_notification>"
            );

        }

        return notifications;
    }

    /**
     * 查看任务状态（不取走）——demo 轮询等待用。
     */
    public String statusOf(String taskId) {
        synchronized (lock) {
            Task task = tasks.get(taskId);
            return task == null ? null : task.status();
        }
    }

    /**
     * 参考实现的 _format_bash_result：
     * 正常退出（含无退出码的错误路径）原样返回输出；
     * 非零退出码加 Error 前缀，让模型看得见失败原因。
     */
    private static String formatResult(
            ShellTool.Outcome outcome
    ) {
        Integer exitCode = outcome.exitCode();

        if (exitCode == null || exitCode == 0) {
            return outcome.output();
        }

        return "Error: command exited with status "
                + exitCode + "\n" + outcome.output();
    }

    private static String preview(
            String value,
            int maximumLength
    ) {
        String text = String.valueOf(value);

        if (text.length() <= maximumLength) {
            return text;
        }

        return text.substring(0, maximumLength);
    }
}
