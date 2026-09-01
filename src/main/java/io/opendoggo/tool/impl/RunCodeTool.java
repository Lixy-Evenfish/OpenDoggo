package io.opendoggo.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.opendoggo.sandbox.SandboxRunner;
import io.opendoggo.tool.ToolHandler;

/**
 * 沙箱执行工具（第十一个，仅父循环）：
 * 把代码交给 SandboxRunner 的一次性容器执行。
 * 无权限规则——沙箱是机制隔离（无网络、无宿主文件、
 * 无宿主环境变量），隔离代替审批；对比 bash 走三道闸门。
 */
public final class RunCodeTool implements ToolHandler {

    private final SandboxRunner runner;

    public RunCodeTool(SandboxRunner runner) {
        this.runner = runner;
    }

    @Override
    public String execute(JsonNode input) {
        String code = input.path("code").asText();

        if (code.isBlank()) {
            return "Error: code cannot be empty";
        }

        return runner.run(
                input.path("language").asText().strip(),
                code
        );
    }

    @Override
    public String name() {
        return "run_code";
    }

    @Override
    public String description() {
        return "Run code in an isolated sandbox: "
                + "no network, no host files, "
                + "no host environment variables. "
                + "Prefer this over bash "
                + "for running code.";
    }

    /** 语言枚举取自沙箱白名单（connect_mcp 的先例）。 */
    @Override
    public JsonNode inputSchema() {
        ObjectNode schema =
                JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");

        ObjectNode properties =
                schema.putObject("properties");

        ObjectNode language =
                properties.putObject("language");
        language.put("type", "string");

        ArrayNode enumNames = language.putArray("enum");
        runner.languages().forEach(enumNames::add);

        properties.putObject("code")
                .put("type", "string");

        schema.putArray("required")
                .add("language")
                .add("code");
        return schema;
    }
}
