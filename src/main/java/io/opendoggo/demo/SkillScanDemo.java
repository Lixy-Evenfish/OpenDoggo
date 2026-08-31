package io.opendoggo.demo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import io.opendoggo.skill.SkillLoader;

/**
 * s07 R1 验收 demo：在临时目录里造 fixture 技能，
 * 打印 SkillLoader.catalog() 逐用例对照期望
 * （完整 frontmatter / 无 frontmatter / 未闭合 / name 留空 /
 * 引号值 / CRLF / 快照语义 / 目录不存在）。
 * 不依赖 API key，不进 REPL。
 */
public final class SkillScanDemo {

    private SkillScanDemo() {
    }

    public static void main(String[] args) throws IOException {
        Path root = Files.createTempDirectory("skills-demo");

        write(root, "code-review/SKILL.md", """
                ---
                name: code-review
                description: Perform thorough code reviews with a checklist.
                ---

                ## Steps
                1. Read the diff
                """);

        // name 留空：回退目录名
        write(root, "nameless/SKILL.md", """
                ---
                name:
                description: frontmatter name 为空走目录名回退
                ---

                body
                """);

        // 无 frontmatter：name 回退目录名，
        // description 回退正文首行（## 前缀与连续空格被归一化）
        write(root, "pdf/SKILL.md", """
                ## Process PDF files  safely
                Extract text and render pages.
                """);

        // 引号值：unquote 剥掉一层成对双引号
        write(root, "quoted/SKILL.md", """
                ---
                name: quoted
                description: "Quoted value"
                ---
                """);

        // 块标量（|）：description 写在缩进续行里，折叠成单行；
        // 续行里的冒号（Keywords:）是内容不是键
        write(root, "multiline/SKILL.md", """
                ---
                name: multiline
                description: |
                  Design and build agents. Use when users:
                  (1) ask to create an agent
                  Keywords: agent, workflow
                ---

                body
                """);

        // 未闭合 frontmatter：整文当正文，description 首行是 "---"
        // （参考实现的真实行为，不是 bug）
        write(root, "unfinished/SKILL.md", """
                ---
                name: unfinished
                description: never parsed
                """);

        // CRLF 行尾：frontmatter 必须照常解析
        write(root, "windows/SKILL.md",
                "---\r\n"
                        + "name: windows\r\n"
                        + "description: CRLF line endings\r\n"
                        + "---\r\n\r\nbody\r\n");

        SkillLoader loader = new SkillLoader(root);
        System.out.println("== catalog（按路径排序，七项） ==");
        System.out.println(loader.catalog());
        System.out.println();

        // 快照语义：构造后再新增技能，目录不变
        write(root, "late/SKILL.md", """
                ---
                name: late
                description: added after construction
                ---
                """);
        System.out.println("== 构造后新增 late（目录应保持六项不变） ==");
        System.out.println(loader.catalog());
        System.out.println();

        // 目录不存在：空注册表，不抛异常
        SkillLoader missing =
                new SkillLoader(root.resolve("no-such-dir"));
        System.out.println("== 目录不存在 ==");
        System.out.println(missing.catalog());
        System.out.println();

        // R3：load() 命中——返回全文原文（含 frontmatter）
        System.out.println("== load 命中 code-review（前 3 行） ==");
        System.out.println(String.join("\n",
                loader.load("code-review")
                        .lines()
                        .limit(3)
                        .toList()));
        System.out.println();

        // R3：load() 未命中——错误字符串，不是异常
        System.out.println("== load 未命中 nope ==");
        System.out.println(loader.load("nope"));
        System.out.println();

        // R3：空注册表上 load——Available 退化为 none
        System.out.println("== 空注册表上 load anything ==");
        System.out.println(missing.load("anything"));
    }

    private static void write(
            Path root,
            String relative,
            String content
    ) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
