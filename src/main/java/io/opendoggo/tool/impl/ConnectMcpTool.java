package io.opendoggo.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.opendoggo.mcp.McpRegistry;
import io.opendoggo.tool.ToolHandler;

/**
 * s14 的 connect_mcp 工具（第十个，仅父循环——
 * 子分发表保持五基础工具口径）：
 * 连接一个 MCP server 并发现它的工具。
 * 连接/命名/授权都在 McpRegistry；
 * 这里是普通工具，照常过 hooks 与分发表，
 * 无权限规则（fail-open，与 todo_write/task/load_skill 同口径）。
 */
public final class ConnectMcpTool implements ToolHandler {

    private final McpRegistry registry;

    public ConnectMcpTool(McpRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String execute(JsonNode input) {
        String name = input.path("name").asText().strip();

        if (name.isEmpty()) {
            return "Error: name cannot be empty";
        }

        return registry.connect(name);
    }

    @Override
    public String name() {
        return "connect_mcp";
    }

    @Override
    public String description() {
        return "Connect to an MCP server and discover its tools.";
    }

    /** 枚举取自注册表（参考实现硬编码 ["docs","deploy"]）。 */
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema =
                JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");

        ObjectNode name =
                schema.putObject("properties").putObject("name");
        name.put("type", "string");

        ArrayNode enumNames = name.putArray("enum");
        registry.serverNames().forEach(enumNames::add);

        schema.putArray("required").add("name");
        return schema;
    }
}
