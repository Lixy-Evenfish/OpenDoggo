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
 三道闸门权限管线，由 Main 注册的 PreToolUse hook
 在 toolDispatch.execute 之前调用：
 
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
            ),
            new Rule(
                    Set.of(BASH),
                    input -> referencesOutsideWorkspace(
                            text(input, "command")
                    ),
                    "Access outside workspace"
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
     * 三道闸门串在一起。
     * 返回 null 允许执行；返回非 null 为拒绝原因，
     * 直接成为带原 tool_use_id 的 tool_result 内容。
     */
    public String check(ContentBlock toolCall) {
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
                    return "Permission denied by deny list";
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
                )
                        ? null
                        : "Permission denied by user";
            }
        }

        return null;
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

    /**
     * 闸门 2 的 bash 跨区规则（教学级启发式）：
     * 按空白切分命令，任何"看起来是路径"的 token——
     * 父目录跳转（.. 段）、~ 开头、或工作区之外的绝对路径——
     * 都视为访问工作区外，升级审批。
     * 引号内含空格的路径、等号粘连的附件（如 --prefix=/x）识别不了。
     */
    private boolean referencesOutsideWorkspace(
            String command
    ) {
        String workdir = workingDirectory.toString();

        for (String token : command.split("\\s+")) {
            String trimmed = stripQuotes(token);

            if (trimmed.isEmpty()) {
                continue;
            }

            boolean parentStep = trimmed.equals("..")
                    || trimmed.startsWith("../")
                    || trimmed.contains("/../");

            boolean homeRelative =
                    trimmed.startsWith("~");

            boolean absoluteOutside =
                    trimmed.startsWith("/")
                            && !trimmed.equals(workdir)
                            && !trimmed.startsWith(
                            workdir + "/"
                    );

            if (parentStep
                    || homeRelative
                    || absoluteOutside) {
                return true;
            }
        }

        return false;
    }

    private static String stripQuotes(String token) {
        if (token.length() >= 2) {
            char first = token.charAt(0);
            char last = token.charAt(token.length() - 1);

            if ((first == '"' && last == '"')
                    || (first == '\''
                    && last == '\'')) {
                return token.substring(
                        1,
                        token.length() - 1
                );
            }
        }

        return token;
    }
}
