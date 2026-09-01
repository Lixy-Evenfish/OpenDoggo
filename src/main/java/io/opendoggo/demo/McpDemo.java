package io.opendoggo.demo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import io.opendoggo.mcp.McpClient;
import io.opendoggo.mcp.McpRegistry;
import io.opendoggo.model.ContentBlock;
import io.opendoggo.permission.PermissionChecker;
import io.opendoggo.tool.ToolDispatch;
import io.opendoggo.tool.impl.ConnectMcpTool;

/**
 * s14 自检 demo（无需 API key）：
 * 用进程内模拟 server 验证 MCP 的连接/发现/命名/授权——
 * McpRegistry 的 connect 语义、分发表登记与 tools 刷新、
 * mcp__ 前缀的宿主策略审批门。
 */
public final class McpDemo {

    private static final ObjectMapper MAPPER =
            new ObjectMapper();

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        McpRegistry registry = new McpRegistry();
        ToolDispatch dispatch = new ToolDispatch();
        dispatch.register(new ConnectMcpTool(registry));

        List<JsonNode> refreshed = new ArrayList<>();
        registry.attach(dispatch, refreshed::add);

        // -- connect 语义 --
        check("unknown server lists options",
                registry.connect("nope"),
                "Unknown server 'nope'."
                        + " Available: docs, deploy");

        check("connect docs discovers tools",
                registry.connect("docs"),
                "Connected to MCP server 'docs'."
                        + " Discovered 2 tools:"
                        + " search, get_version");

        check("connect is idempotent",
                registry.connect("docs"),
                "MCP server 'docs' already connected");

        // -- 分发表登记 + 定义渲染 --
        check("prefixed name registered",
                dispatch.hasTool("mcp__docs__search"),
                true);
        check("second tool registered",
                dispatch.hasTool("mcp__docs__get_version"),
                true);

        ArrayNode definitions = (ArrayNode)
                refreshed.get(refreshed.size() - 1);
        JsonNode searchDef = findByName(
                definitions,
                "mcp__docs__search"
        );
        check("definition carries input_schema",
                searchDef.path("input_schema")
                        .path("properties")
                        .path("query")
                        .path("type")
                        .asText(),
                "string");
        check("connect_mcp still in pool",
                findByName(definitions, "connect_mcp")
                        .path("input_schema")
                        .path("properties")
                        .path("name")
                        .path("enum")
                        .toString(),
                "[\"docs\",\"deploy\"]");

        // -- 调用边界（经 ToolDispatch，模型视角）--
        check("search executes",
                dispatch.execute(ContentBlock.toolUse(
                        "t1",
                        "mcp__docs__search",
                        MAPPER.readTree(
                                "{\"query\": \"agent hooks\"}"
                        )
                )),
                "[docs] Found 3 results for 'agent hooks'");

        check("get_version executes",
                dispatch.execute(ContentBlock.toolUse(
                        "t2",
                        "mcp__docs__get_version",
                        MAPPER.readTree("{}")
                )),
                "[docs] API v2.1.0");

        check("missing argument returns MCP error",
                dispatch.execute(ContentBlock.toolUse(
                        "t3",
                        "mcp__docs__search",
                        MAPPER.readTree("{}")
                )),
                "MCP error: IllegalArgumentException:"
                        + " Missing required argument: query");

        // -- 宿主侧策略 --
        check("docs/search allowed",
                registry.isAllowed("mcp__docs__search"),
                true);
        check("unconfigured tool defaults to confirm",
                registry.isAllowed("mcp__deploy__trigger"),
                false);

        // -- 审批门（脚本化 ApprovalPrompt）--
        int[] asks = {0};
        PermissionChecker checker = new PermissionChecker(
                Path.of("").toAbsolutePath(),
                (name, input, reason) -> {
                    asks[0]++;
                    return false;
                },
                registry::isAllowed
        );

        check("allowed MCP tool runs without asking",
                checker.check(ContentBlock.toolUse(
                        "t4",
                        "mcp__docs__search",
                        MAPPER.readTree(
                                "{\"query\": \"x\"}"
                        )
                )),
                null);
        check("no approval prompt fired", asks[0], 0);

        check("deploy connects",
                registry.connect("deploy"),
                "Connected to MCP server 'deploy'."
                        + " Discovered 2 tools:"
                        + " trigger, status");

        check("deploy/trigger denied by user",
                checker.check(ContentBlock.toolUse(
                        "t5",
                        "mcp__deploy__trigger",
                        MAPPER.readTree(
                                "{\"service\": \"web\"}"
                        )
                )),
                "Permission denied by user");
        check("approval prompt fired once", asks[0], 1);

        check("deploy/status allowed without asking",
                checker.check(ContentBlock.toolUse(
                        "t6",
                        "mcp__deploy__status",
                        MAPPER.readTree(
                                "{\"service\": \"web\"}"
                        )
                )),
                null);
        check("still exactly one prompt", asks[0], 1);

        check("deploy/status executes",
                dispatch.execute(ContentBlock.toolUse(
                        "t7",
                        "mcp__deploy__status",
                        MAPPER.readTree(
                                "{\"service\": \"web\"}"
                        )
                )),
                "[deploy] web: running (v1.4.2)");

        // -- 命名归一化与撞名 --
        McpRegistry hostile = new McpRegistry(Map.of(
                "docs.one", () -> serverWith("get.version"),
                "docs_one", () -> serverWith("get_version")
        ));
        ToolDispatch hostileDispatch = new ToolDispatch();
        hostile.attach(hostileDispatch, ignored -> { });

        hostile.connect("docs.one");
        check("disallowed characters normalized",
                hostileDispatch.hasTool(
                        "mcp__docs_one__get_version"),
                true);

        check("collision after normalization rejected",
                thrown(() -> hostile.connect("docs_one"))
                        instanceof IllegalArgumentException,
                true);

        // -- McpClient 校验 --
        McpClient bad = new McpClient("bad");
        check("duplicate names rejected",
                thrown(() -> bad.register(
                        List.of(json("{\"name\": \"a\"}"),
                                json("{\"name\": \"a\"}")),
                        Map.of("a", ignored -> "ok")
                )) instanceof IllegalArgumentException,
                true);
        check("missing handler rejected",
                thrown(() -> bad.register(
                        List.of(json("{\"name\": \"a\"}")),
                        Map.of()
                )) instanceof IllegalArgumentException,
                true);

        McpClient standalone = new McpClient("solo");
        standalone.register(
                List.of(json("{\"name\": \"only\"}")),
                Map.of("only", ignored -> "ok")
        );
        check("unknown tool at call boundary",
                standalone.callTool(
                        "nope",
                        MAPPER.createObjectNode()
                ),
                "MCP error: unknown tool 'nope'");

        // -- connect_mcp 工具本体 --
        ConnectMcpTool tool = new ConnectMcpTool(registry);
        check("blank name guard",
                tool.execute(
                        MAPPER.readTree("{\"name\": \" \"}")
                ),
                "Error: name cannot be empty");

        System.out.println();
        System.out.println(
                "McpDemo: " + passed + " passed, "
                        + failed + " failed"
        );

        if (failed > 0) {
            System.exit(1);
        }
    }

    private static JsonNode findByName(
            ArrayNode definitions,
            String name
    ) {
        for (JsonNode def : definitions) {
            if (name.equals(def.path("name").asText())) {
                return def;
            }
        }

        return MAPPER.createObjectNode();
    }

    private static McpClient serverWith(String toolName) {
        McpClient server = new McpClient("hostile");
        server.register(
                List.of(json(
                        "{\"name\": \"" + toolName + "\"}"
                )),
                Map.of(toolName, args -> "ok")
        );
        return server;
    }

    private static Throwable thrown(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            return exception;
        }

        return null;
    }

    private static JsonNode json(String literal) {
        try {
            return MAPPER.readTree(literal);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void check(
            String label,
            Object actual,
            Object expected
    ) {
        boolean ok = Objects.equals(actual, expected);

        if (ok) {
            passed++;
        } else {
            failed++;
        }

        System.out.println(
                (ok ? "PASS" : "FAIL") + " - " + label
        );

        if (!ok) {
            System.out.println("  expected: " + expected);
            System.out.println("  actual:   " + actual);
        }
    }
}
