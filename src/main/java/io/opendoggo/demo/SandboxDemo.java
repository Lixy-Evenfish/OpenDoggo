package io.opendoggo.demo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.opendoggo.sandbox.SandboxRunner;
import io.opendoggo.tool.ToolDispatch;
import io.opendoggo.tool.impl.RunCodeTool;

/**
 * 沙箱自检 demo（无需 API key；需要本机 Docker，
 * python 语言需 python:3.12-slim 镜像）：
 * 验证 run_code 的机制隔离——宿主环境变量、网络、
 * 文件系统、资源超时，加上语言白名单、退出码、
 * stderr 合并、输出截断与全量落盘。
 */
public final class SandboxDemo {

    private static final ObjectMapper MAPPER =
            new ObjectMapper();

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        // 超时调短到 10s：超时击杀检查只等 5s
        // （容器内自杀时限 = 10 - 5）。
        SandboxRunner runner = new SandboxRunner(
                Path.of("").toAbsolutePath().normalize(),
                10
        );
        RunCodeTool tool = new RunCodeTool(runner);

        // -- 工具定义 --
        ToolDispatch dispatch = new ToolDispatch();
        dispatch.register(tool);

        check("run_code registered",
                dispatch.hasTool("run_code"),
                true);

        check("schema enum lists languages",
                findByName(
                        dispatch.toolDefinitions(),
                        "run_code"
                ).path("input_schema")
                        .path("properties")
                        .path("language")
                        .path("enum")
                        .toString(),
                "[\"python\",\"node\",\"bash\"]");

        // -- 参数校验 --
        ObjectNode blankCode = MAPPER.createObjectNode();
        blankCode.put("language", "node");
        blankCode.put("code", "   ");

        check("empty code rejected",
                tool.execute(blankCode),
                "Error: code cannot be empty");

        check("unknown language lists options",
                runner.run("ruby", "puts 1"),
                "Error: unsupported language 'ruby'."
                        + " Available: python, node, bash");

        // -- 正常执行（node：本机已有镜像）--
        String hello = runner.run(
                "node",
                "console.log(\"hello from sandbox\")"
        );
        check("node exit code 0",
                hello.startsWith("exit code: 0"),
                true);
        check("node stdout",
                hello.contains("hello from sandbox"),
                true);

        String failing = runner.run(
                "node",
                "console.error(\"to-stderr\");"
                        + " process.exit(3)"
        );
        check("exit code propagated",
                failing.startsWith("exit code: 3"),
                true);
        check("stderr merged into output",
                failing.contains("to-stderr"),
                true);

        // -- 隔离三件套：env / 网络 / 文件系统 --
        check("host env invisible (node)",
                runner.run(
                        "node",
                        "console.log(process.env"
                                + ".ANTHROPIC_API_KEY)"
                ).contains("undefined"),
                true);

        check("network blocked",
                runner.run(
                        "node",
                        """
                        fetch("http://example.com")
                          .then(r => console.log(
                            "NET OK", r.status))
                          .catch(e => console.log(
                            "NET BLOCKED",
                            e.cause ? e.cause.code
                                    : e.message))
                        """
                ).contains("NET BLOCKED"),
                true);

        String filesystem = runner.run(
                "bash",
                """
                echo "-- /sandbox:"; ls -a /sandbox
                echo "-- host files:"
                test -e /AGENTS.md && echo LEAKED || echo ISOLATED
                echo "-- read-only root:"; touch /etc/x 2>&1
                """
        );
        check("scratch dir mounted with staged file",
                filesystem.contains("script.sh"),
                true);
        check("host files invisible",
                filesystem.contains("ISOLATED"),
                true);
        check("root filesystem read-only",
                filesystem.contains("Read-only file system"),
                true);

        check("host env invisible (bash)",
                runner.run(
                        "bash",
                        "printenv | grep ANTHROPIC"
                                + " || echo NO_HOST_ENV"
                ).contains("NO_HOST_ENV"),
                true);

        // -- 资源限额：超时击杀 --
        check("timeout killed",
                runner.run(
                        "bash",
                        "while true; do :; done"
                ).startsWith("Error: Sandbox timeout"),
                true);

        // -- 输出截断 + 全量落盘 --
        String big = runner.run(
                "node",
                "for (let i = 0; i < 1000; i++)"
                        + " console.log(\"x\".repeat(50))"
        );
        check("output truncated",
                big.contains("truncated"),
                true);

        Path fullOutput = Path.of(
                big.substring(
                        big.indexOf("Full output: ") + 13
                ).strip()
        );
        check("full output saved to disk",
                Files.exists(fullOutput),
                true);
        check("saved output is complete",
                Files.readString(fullOutput)
                        .strip()
                        .split("\n").length,
                1000);

        // -- python（python:3.12-slim 镜像）--
        check("python hello",
                runner.run(
                        "python",
                        "print(\"hello from python\")"
                ).contains("hello from python"),
                true);

        String traceback = runner.run(
                "python",
                "raise ValueError(\"boom\")"
        );
        check("python traceback with exit code",
                traceback.startsWith("exit code: 1")
                        && traceback.contains(
                        "ValueError: boom"),
                true);

        System.out.println();
        System.out.println(
                "SandboxDemo: " + passed
                        + " passed, " + failed + " failed"
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
