package io.opendoggo.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * s14：单个 MCP server 的进程内替身。
 *
 * register 扮演 tools/list（拿到 server 的工具清单），
 * callTool 扮演 tools/call（把调用转给 server 的处理函数）。
 * 真实 MCP 的 JSON-RPC 传输不在本课范围——这里就是
 * 同进程的普通方法调用；错误作为 "MCP error: ..." 字符串
 * 回给模型，循环不崩溃。
 */
public final class McpClient {

    private final String name;

    private final List<JsonNode> tools = new ArrayList<>();

    private final Map<String, Function<JsonNode, String>> handlers =
            new LinkedHashMap<>();

    public McpClient(String name) {
        this.name = Objects.requireNonNull(
                name,
                "name cannot be null"
        );
    }

    public String name() {
        return name;
    }

    /** 原始 MCP 工具定义（驼峰 inputSchema + annotations）。 */
    public List<JsonNode> tools() {
        return List.copyOf(tools);
    }

    /**
     * tools/list 替身：登记工具定义与处理函数。
     * 名字必须非空、server 内不重名、每个名字都有处理函数，
     * 否则 IllegalArgumentException（对应参考实现的 ValueError，
     * 由外层转成 "Error: ..." tool_result）。
     */
    public void register(
            List<JsonNode> toolDefs,
            Map<String, Function<JsonNode, String>> handlerMap
    ) {
        List<String> names = new ArrayList<>();

        for (JsonNode def : toolDefs) {
            String toolName = def.path("name").asText("");

            if (toolName.isBlank()) {
                throw new IllegalArgumentException(
                        "Every MCP tool needs a non-empty name"
                );
            }

            names.add(toolName);
        }

        if (names.stream().distinct().count() != names.size()) {
            throw new IllegalArgumentException(
                    "Duplicate MCP tool name on server '"
                            + name + "'"
            );
        }

        for (String toolName : names) {
            if (!handlerMap.containsKey(toolName)) {
                throw new IllegalArgumentException(
                        "Missing MCP handler: " + toolName
                );
            }
        }

        tools.clear();
        tools.addAll(toolDefs);
        handlers.clear();
        handlers.putAll(handlerMap);
    }

    /**
     * tools/call 替身：用 server 的原始工具名调用。
     * 未知名与处理函数抛出的异常（含缺参数）
     * 都变成 "MCP error: ..." 返回，模型下一轮可自行纠正。
     */
    public String callTool(String toolName, JsonNode input) {
        Function<JsonNode, String> handler = handlers.get(toolName);

        if (handler == null) {
            return "MCP error: unknown tool '" + toolName + "'";
        }

        try {
            return handler.apply(input);
        } catch (RuntimeException exception) {
            return "MCP error: "
                    + exception.getClass().getSimpleName()
                    + ": " + exception.getMessage();
        }
    }
}
