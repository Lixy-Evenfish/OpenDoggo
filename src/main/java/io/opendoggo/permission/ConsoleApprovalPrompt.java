package io.opendoggo.permission;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 闸门 3 的控制台实现：打印原因与工具调用，
 * 提示 Allow? [y/N]，默认拒绝，仅 y/yes 放行。
 *
 * 与 REPL 共享同一个 BufferedReader，
 * 避免两条读入路径争抢 System.in。
 */
public final class ConsoleApprovalPrompt
        implements ApprovalPrompt {

    private final BufferedReader reader;

    public ConsoleApprovalPrompt(BufferedReader reader) {
        this.reader = Objects.requireNonNull(
                reader,
                "reader cannot be null"
        );
    }

    @Override
    public boolean ask(
            String toolName,
            JsonNode input,
            String reason
    ) {
        System.out.println();
        System.out.println("[permission] " + reason);
        System.out.println(
                "   Tool: " + toolName
                        + "(" + input + ")"
        );
        System.out.print("   Allow? [y/N] ");
        System.out.flush();

        String choice;

        try {
            choice = reader.readLine();
        } catch (IOException exception) {
            choice = null;
        }

        String normalized = choice == null
                ? ""
                : choice.strip()
                        .toLowerCase(Locale.ROOT);

        return normalized.equals("y")
                || normalized.equals("yes");
    }
}
