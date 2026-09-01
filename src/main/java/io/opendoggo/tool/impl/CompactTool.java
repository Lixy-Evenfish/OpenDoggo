package io.opendoggo.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.opendoggo.tool.ToolHandler;

/**
 * s08 R3 的 compact 工具：模型在一个阶段结束后
 * 主动请求压缩——表示后续工作只需要当前阶段的摘要。
 *
 * 这是一个"仅定义"的工具：注册它是为了让
 * ToolDispatch.toolDefinitions() 把定义带进
 * 发给模型的 tools 数组；真正的调用在
 * AgentLoop 里按名拦截（在 PreToolUse hooks
 * 之前——参考实现里 compact 不经过 execute_tool，
 * 也就没有权限检查），execute 方法是永远
 * 走不到的兜底，返回与拦截一致的字面确认串。
 *
 * 仅注册进父循环分发表（子代理保持五基础工具口径）。
 */
public final class CompactTool implements ToolHandler {

    /**
     * 拦截路径返回的 tool_result 字面串
     * （AgentLoop 里也持有同一字面量）。
     */
    public static final String BATCH_RESULT =
            "Compaction requested after this tool batch.";

    @Override
    public String execute(JsonNode input) {
        // 兜底：正常流程到不了这里（AgentLoop 先拦截）。
        return BATCH_RESULT;
    }

    @Override
    public String name() {
        return "compact";
    }

    @Override
    public String description() {
        return "Summarize earlier conversation "
                + "to free context space.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema =
                JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        schema.putObject("properties");
        return schema;
    }
}
