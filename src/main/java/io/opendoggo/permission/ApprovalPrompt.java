package io.opendoggo.permission;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 闸门 3 的审批回调。权限层与 AgentLoop 不读取终端。
 */
public interface ApprovalPrompt {

    /**
     * 返回 true 表示允许，false 表示拒绝（默认）。
     */
    boolean ask(
            String toolName,
            JsonNode input,
            String reason
    );
}
