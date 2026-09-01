package io.opendoggo.permission;

/**
 * s14：MCP 外部工具的宿主侧授权策略源。
 *
 * 授权只来自宿主配置，从不来自 server 的
 * readOnlyHint/destructiveHint 自我标注。
 * 返回 false = 需要走闸门 3 用户审批
 * （未配置的外部工具默认确认）。
 */
@FunctionalInterface
public interface McpToolPolicy {

    /** 前缀名（mcp__server__tool）是否免审批。 */
    boolean isAllowed(String toolName);
}
