package io.opendoggo.permission;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

import io.opendoggo.model.ContentBlock;

/*
 三道闸门权限管线，由 AgentLoop 在toolDispatch.execute 之前调用：
 
 硬拒绝表：永远禁止，直接拒绝不询问；
 规则匹配：命中后升级到闸门 3；
 用户审批：经 ApprovalPrompt 询问，默认拒绝。

 */
public final class PermissionChecker {

    private static final String BASH = "bash";

    /** 闸门 1：永远禁止的命令片段，仅对 bash 生效。 */
    private static final List<String> DENY_LIST =
            List.of(
                    "rm -rf /",
                    "sudo",
                    "shutdown",
                    "reboot",
                    "mkfs",
                    "dd if=",
                    "> /dev/sda"
            );

    /**
     * 闸门 2：rm/del 必须是独立命令词，
     * 因此 model、delimiter、echo del x 都不会命中。
     */
    private static final Pattern DESTRUCTIVE_COMMAND_WORD =
            Pattern.compile(
                    "(?i)(?:^|[;&|()\\n])\\s*"
                            + "(?:rm|del)(?=\\s|$|[;&|()])"
            );

    private static final List<String> DESTRUCTIVE_KEYWORDS =
            List.of("rm ", "> /etc/", "chmod 777");

    /**
     * 规则 = 适用工具 + 检查条件 + 命中消息，
     * 对应参考实现的 PERMISSION_RULES。
     */
    private record Rule(
            Set<String> tools,
            Predicate<JsonNode> check,
            String message
    ) {
    }

    private final Path workingDirectory;
    private final ApprovalPrompt approvalPrompt;

    private final List<Rule> rules = List.of(
            new Rule(
                    Set.of(
                            "read_file",
                            "write_file",
                            "edit_file"
                    ),
                    input -> escapesWorkspace(
                            text(input, "path")
                    ),
                    "Access outside workspace"
            ),
            new Rule(
                    Set.of(BASH),
                    input -> isDestructive(
                            text(input, "command")
                    ),
                    "Potentially destructive command"
            )
    );

    public PermissionChecker(
            Path workingDirectory,
            ApprovalPrompt approvalPrompt
    ) {
        this.workingDirectory =
                Objects.requireNonNull(
                        workingDirectory,
                        "workingDirectory cannot be null"
                )
                        .toAbsolutePath()
                        .normalize();

        this.approvalPrompt =
                Objects.requireNonNull(
                        approvalPrompt,
                        "approvalPrompt cannot be null"
                );
    }

    /**
     * 三道闸门串在一起，返回 true 才允许执行。
     */
    public boolean check(ContentBlock toolCall) {
        JsonNode input = toolCall.input();

        // 闸门 1：硬拒绝，直接拦截不询问。
        if (BASH.equals(toolCall.name())) {
            String command = text(input, "command");

            for (String pattern : DENY_LIST) {
                if (command.contains(pattern)) {
                    System.out.println(
                            "[blocked] '" + pattern
                                    + "' is on the deny list"
                    );
                    return false;
                }
            }
        }

        // 闸门 2 + 3：规则命中后交给用户审批。
        for (Rule rule : rules) {
            if (rule.tools().contains(toolCall.name())
                    && rule.check().test(input)) {

                return approvalPrompt.ask(
                        toolCall.name(),
                        input,
                        rule.message()
                );
            }
        }

        return true;
    }

    private static String text(
            JsonNode input,
            String field
    ) {
        return input == null
                ? ""
                : input.path(field).asText("");
    }

    /**
     * 闸门 2 的文件规则：解析后路径逸出工作区。
     * 空路径不升级审批，交给工具自行报错。
     */
    private boolean escapesWorkspace(String rawPath) {
        if (rawPath.isBlank()) {
            return false;
        }

        Path resolved = workingDirectory
                .resolve(rawPath.strip())
                .normalize();

        return !resolved.startsWith(workingDirectory);
    }

    private static boolean isDestructive(String command) {
        if (DESTRUCTIVE_COMMAND_WORD
                .matcher(command)
                .find()) {
            return true;
        }

        return DESTRUCTIVE_KEYWORDS.stream()
                .anyMatch(command::contains);
    }
}
