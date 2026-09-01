package io.opendoggo.mcp;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import io.opendoggo.tool.ToolDispatch;
import io.opendoggo.tool.ToolHandler;

/**
 * s14：MCP 注册表——连接、命名、授权的宿主侧数据源。
 *
 * connect 对应参考实现的 connect_mcp + assemble_tool_pool：
 * 连接是低频事件，Java 端不在每轮重组工具池，而是 connect 时
 * 把前缀化工具登记进父分发表（attach 注入），再刷新 client 的
 * tools 数组（Main 传 modelClient::updateTools）。
 * 授权只来自宿主策略 hostPolicy——server 的
 * readOnlyHint/destructiveHint 是自我标注，永不作为授权依据。
 */
public final class McpRegistry {

    /** 参考实现的 _DISALLOWED_CHARS：模型工具名字母表之外的字符。 */
    private static final Pattern DISALLOWED_CHARS =
            Pattern.compile("[^a-zA-Z0-9_-]");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, Supplier<McpClient>> servers =
            new LinkedHashMap<>();

    private final Map<String, McpClient> connected =
            new LinkedHashMap<>();

    /** 前缀名 → 是否免审批；未登记的外部工具一律需要确认。 */
    private final Map<String, Boolean> allowedByTool =
            new LinkedHashMap<>();

    private ToolDispatch dispatch;
    private Consumer<JsonNode> toolsSink;

    public McpRegistry() {
        servers.put("docs", McpRegistry::mockDocs);
        servers.put("deploy", McpRegistry::mockDeploy);
    }

    /** 测试/扩展用：追加自定义 server 工厂（教学级模拟 server）。 */
    public McpRegistry(Map<String, Supplier<McpClient>> extraServers) {
        this();
        servers.putAll(extraServers);
    }

    /** 注入父分发表与 tools 刷新回调（连接前必须完成）。 */
    public void attach(
            ToolDispatch dispatch,
            Consumer<JsonNode> toolsSink
    ) {
        this.dispatch = Objects.requireNonNull(
                dispatch,
                "dispatch cannot be null"
        );
        this.toolsSink = Objects.requireNonNull(
                toolsSink,
                "toolsSink cannot be null"
        );
    }

    public Set<String> serverNames() {
        return servers.keySet();
    }

    /** PermissionChecker 的 mcp__ 策略源：默认全部需要确认。 */
    public boolean isAllowed(String toolName) {
        return allowedByTool.getOrDefault(toolName, false);
    }

    /**
     * connect_mcp 的处理体：幂等；未知名列出可选项；
     * 成功后把该 server 的工具登记进分发表并刷新 tools。
     */
    public String connect(String name) {
        if (connected.containsKey(name)) {
            return "MCP server '" + name + "' already connected";
        }

        Supplier<McpClient> factory = servers.get(name);

        if (factory == null) {
            return "Unknown server '" + name
                    + "'. Available: "
                    + String.join(", ", servers.keySet());
        }

        if (dispatch == null) {
            throw new IllegalStateException(
                    "McpRegistry is not attached to a dispatch"
            );
        }

        McpClient server = factory.get();
        connected.put(name, server);

        for (JsonNode def : server.tools()) {
            registerTool(name, server, def);
        }

        refreshTools();

        List<String> names = server.tools().stream()
                .map(def -> def.path("name").asText())
                .toList();

        return "Connected to MCP server '" + name
                + "'. Discovered " + names.size()
                + " tools: " + String.join(", ", names);
    }

    /**
     * assemble_tool_pool 的单工具版本：归一化、加前缀、
     * 64 字符上限、撞名检测（分发表里已有的一切名字都在
     * 检测范围——MCP 工具不能遮蔽内置工具或彼此）、
     * inputSchema 校验，最后登记处理与宿主策略。
     */
    private void registerTool(
            String serverName,
            McpClient server,
            JsonNode def
    ) {
        String rawName = def.path("name").asText();
        String prefixed = "mcp__" + normalize(serverName)
                + "__" + normalize(rawName);

        if (prefixed.length() > 64) {
            throw new IllegalArgumentException(
                    "MCP tool name is longer than 64 characters: "
                            + prefixed
            );
        }

        if (dispatch.hasTool(prefixed)) {
            throw new IllegalArgumentException(
                    "MCP tool name collision after normalization: "
                            + prefixed
            );
        }

        JsonNode schema = def.path("inputSchema");

        if (schema.isMissingNode()) {
            schema = JsonNodeFactory.instance.objectNode();
        }

        if (!schema.isObject()
                || !"object".equals(
                schema.path("type").asText("object"))) {
            throw new IllegalArgumentException(
                    "Invalid input schema for MCP tool '"
                            + serverName + "'/'" + rawName + "'"
            );
        }

        dispatch.register(new McpToolHandler(
                server,
                prefixed,
                rawName,
                def.path("description").asText(""),
                schema
        ));
        allowedByTool.put(prefixed, hostPolicy(serverName, rawName));
    }

    /** 字母表之外的字符替换成下划线；归一化后为空直接拒绝。 */
    private static String normalize(String name) {
        String normalized =
                DISALLOWED_CHARS.matcher(name).replaceAll("_");

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "MCP names cannot normalize to an empty string"
            );
        }

        return normalized;
    }

    /**
     * 宿主侧授权（参考实现的 MCP_HOST_POLICY）：
     * 只信任这里；未配置的外部工具默认需要确认。
     */
    private static boolean hostPolicy(String server, String tool) {
        return switch (server + "/" + tool) {
            case "docs/search", "docs/get_version", "deploy/status" -> true;
            default -> false;
        };
    }

    private void refreshTools() {
        toolsSink.accept(dispatch.toolDefinitions());
    }

    /**
     * 转发适配器：模型看到前缀名（name/inputSchema 出定义），
     * 调用走 server 的原始工具名。每实例捕获自己的
     * client 与 rawName——不存在参考实现用默认参数
     * 规避的循环闭包问题。
     */
    private record McpToolHandler(
            McpClient client,
            String prefixedName,
            String rawName,
            String description,
            JsonNode schema
    ) implements ToolHandler {

        @Override
        public String name() {
            return prefixedName;
        }

        @Override
        public JsonNode inputSchema() {
            return schema;
        }

        @Override
        public String execute(JsonNode input) {
            return client.callTool(rawName, input);
        }
    }

    // -- 进程内模拟 server（docs / deploy）--

    private static McpClient mockDocs() {
        McpClient server = new McpClient("docs");

        server.register(
                List.of(
                        json("""
                                {
                                  "name": "search",
                                  "description": "Search the documentation.",
                                  "inputSchema": {
                                    "type": "object",
                                    "properties": {"query": {"type": "string"}},
                                    "required": ["query"]
                                  },
                                  "annotations": {"readOnlyHint": true}
                                }"""),
                        json("""
                                {
                                  "name": "get_version",
                                  "description": "Get the documentation API version.",
                                  "inputSchema": {
                                    "type": "object",
                                    "properties": {}
                                  },
                                  "annotations": {"readOnlyHint": true}
                                }""")
                ),
                Map.of(
                        "search", args -> "[docs] Found 3 results for '"
                                + arg(args, "query") + "'",
                        "get_version", args -> "[docs] API v2.1.0"
                )
        );

        return server;
    }

    private static McpClient mockDeploy() {
        McpClient server = new McpClient("deploy");

        server.register(
                List.of(
                        json("""
                                {
                                  "name": "trigger",
                                  "description": "Trigger a deployment.",
                                  "inputSchema": {
                                    "type": "object",
                                    "properties": {"service": {"type": "string"}},
                                    "required": ["service"]
                                  },
                                  "annotations": {"destructiveHint": true}
                                }"""),
                        json("""
                                {
                                  "name": "status",
                                  "description": "Check deployment status.",
                                  "inputSchema": {
                                    "type": "object",
                                    "properties": {"service": {"type": "string"}},
                                    "required": ["service"]
                                  },
                                  "annotations": {"readOnlyHint": true}
                                }""")
                ),
                Map.of(
                        "trigger", args -> "[deploy] Triggered: "
                                + arg(args, "service"),
                        "status", args -> "[deploy] " + arg(args, "service")
                                + ": running (v1.4.2)"
                )
        );

        return server;
    }

    /** mock 处理函数的必填参数读取：缺失即报错（server 端校验）。 */
    private static String arg(JsonNode input, String name) {
        JsonNode value = input.get(name);

        if (value == null || value.isNull()) {
            throw new IllegalArgumentException(
                    "Missing required argument: " + name
            );
        }

        return value.asText();
    }

    private static JsonNode json(String literal) {
        try {
            return MAPPER.readTree(literal);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Bad mock tool JSON",
                    exception
            );
        }
    }
}
