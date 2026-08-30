package io.opendoggo.permission;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 闸门 3 的审批回调。
 * 权限层与 AgentLoop 都不碰控制台，
 * 由 Main 的 REPL 提供实现。
 */
public interface ApprovalPrompt {

    /**
     * 打印原因与工具调用并询问用户。
     * 返回 true 表示允许，false 表示拒绝（默认）。
     */
    boolean ask(
            String toolName,
            JsonNode input,
            String reason
    );
}
