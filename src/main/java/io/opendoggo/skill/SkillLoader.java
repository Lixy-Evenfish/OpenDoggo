package io.opendoggo.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * s07 技能加载：启动时扫描 skills 目录下一级的 SKILL.md
 * （恰好一层目录深，按路径排序）构建内存注册表，
 * 对应参考实现 references/code.py 的 SkillLoader（53–122 行）。
 *
 * frontmatter 全程容错：缺失、未闭合、行格式不合法一律降级为
 * “无元数据”，任何清单文件都不让扫描抛异常；name 回退目录名，
 * description 回退正文首行并做空白归一化；content 存全文原文
 * （含 frontmatter）。快照语义：仅构造时扫描一次，运行期不刷新。
 * 已知教学级偏差：平铺 key:value 解析分不清 YAML 非字符串标量
 * （如 name: 123 得到字符串 "123"，参考实现会回退目录名）；
 * 块标量（| 与 >）做了最小支持——缩进续行按空格拼成单行。
 */
public class SkillLoader {
    private record Skill(String name, String description, String content) { }

    private record Manifest(Map<String, String> fields, String body) { }

    private final Path skillsDir;
    private final Map<String, Skill> skills = new LinkedHashMap<>();
 
    public SkillLoader(Path skillsDir) {
        this.skillsDir = skillsDir.toAbsolutePath().normalize();
        scan();
    }

    private void scan() {
        skills.clear();
        if (!Files.isDirectory(skillsDir)) {
            return;              // 目录不存在 → 空注册表，不是错误
        }
        List<Path> manifests = new ArrayList<>();
        try (DirectoryStream<Path> children =
                Files.newDirectoryStream(skillsDir)) {
            for (Path child : children) {
                if (!Files.isDirectory(child)) {
                    continue;      // 只看子目录
                }
                Path manifest = child.resolve("SKILL.md");
                if (!Files.isRegularFile(manifest)) {
                    continue;      // 没有 SKILL.md 的目录跳过
                }
                manifests.add(manifest);
            }
        } catch (IOException
                | DirectoryIteratorException exception) {
            // DirectoryIteratorException：遍历中途出错
            // 会包在里面抛出（unchecked）。
            return;              // 目录不可读/读不全 → 按空处理
        }

        // 参考实现 sorted(glob(...))：按相对路径字符串排序，目录输出才是确定的
        manifests.sort(Comparator.comparing(
                path -> skillsDir.relativize(path).toString()));

        for (Path manifest : manifests) {
            if (!manifest.normalize().startsWith(skillsDir)) {
                continue;        
            }

            String content;
            try {
                content = Files.readString(
                        manifest, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                System.err.println(
                        "[skills] skip unreadable "
                                + manifest + ": "
                                + exception.getMessage());
                continue;
            }

            Manifest parsed = parseFrontmatter(content);
            Map<String, String> fields = parsed.fields();
            String body = parsed.body();

            // name 回退链：frontmatter name → 父目录名
            String rawName = fields.get("name");
            String name = rawName == null ? "" : rawName.strip();
            if (name.isEmpty()) {
                name = manifest
                        .getParent()
                        .getFileName()
                        .toString();
            }

            // description 回退链：frontmatter description → 正文首行；
            // 两种来源都要过归一化（对齐参考实现 95–102 行）。
            String rawDescription = fields.get("description");
            String description =
                    rawDescription == null ? "" : rawDescription.strip();
            if (description.isEmpty()) {
                description = body
                        .lines()
                        .findFirst()
                        .orElse("");
            }
            description = normalizeDescription(description);

            // content 存全文原文（含 frontmatter），load 时原样返回
            skills.put(name, new Skill(name, description, content));
        }
    }

    private static Manifest parseFrontmatter(String text) {
        List<String> lines = text.lines().toList();

        if (lines.isEmpty() || !lines.get(0).equals("---")) {
            return new Manifest(Map.of(), text);   // 无 frontmatter：整文当正文
        }

        int closing = -1;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).equals("---")) {
                closing = i;
                break;
            }
        }
        if (closing < 0) {
            return new Manifest(Map.of(), text);   // 未闭合：同样整文当正文
        }

        Map<String, String> fields = new LinkedHashMap<>();
        List<String> block = lines.subList(1, closing);
        for (int i = 0; i < block.size(); ) {
            String line = block.get(i);
            int sep = line.indexOf(':');
            if (sep <= 0) {
                i++;                               // 无冒号/键为空 → 忽略该行
                continue;
            }
            String key = line.substring(0, sep).strip();
            String value = line.substring(sep + 1).strip();

            // YAML 块标量（| 与 >）：值写在后续缩进续行里，
            // 按空格连接成单行——参考实现经 PyYAML 解析 +
            // normalizeDescription 空白折叠得到同样效果。
            // 续行里的冒号（如 "Keywords: agent"）是内容不是键。
            if (value.startsWith("|") || value.startsWith(">")) {
                List<String> parts = new ArrayList<>();
                i++;
                while (i < block.size()
                        && !block.get(i).isBlank()
                        && (block.get(i).startsWith(" ")
                            || block.get(i).startsWith("\t"))) {
                    parts.add(block.get(i).strip());
                    i++;
                }
                fields.put(key, String.join(" ", parts));
                continue;
            }

            fields.put(key, unquote(value));
            i++;
        }

        String body = String.join("\n", lines.subList(closing + 1, lines.size())).strip();
        return new Manifest(fields, body);
    }

    /** 剥掉一层成对的单/双引号（对齐 yaml.safe_load 的去引号）。 */
    private static String unquote(String value) {
        boolean quotedDouble = value.startsWith("\"")
                && value.endsWith("\"");
        boolean quotedSingle = value.startsWith("'")
                && value.endsWith("'");

        if (value.length() >= 2 && (quotedDouble || quotedSingle)) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * 对齐参考实现 " ".join(s.lstrip("# ").split())：
     * 去行首 #/空格，再按任意空白切分、单空格连接
     * （顺带去首尾空白；先 strip 再切分，避免行首空白
     * 在 split 里产生空元素）。
     */
    private static String normalizeDescription(String raw) {
        String text = raw
                .replaceFirst("^[# ]+", "")
                .strip();
        return String.join(" ", text.split("\\s+"));
    }

    /** 目录文本：每技能一行 "- name: description"；空注册表给占位符。 */
    public String catalog() {
        if (skills.isEmpty()) {
            return "(no skills found)";
        }
        List<String> entries = new ArrayList<>();
        for (Skill skill : skills.values()) {
            entries.add("- " + skill.name()
                    + ": " + skill.description());
        }
        return String.join("\n", entries);
    }

    /**
     * 按名称取回完整 SKILL.md 原文（含 frontmatter）。
     * 未命中返回错误字符串（列出可用技能），不抛异常——
     * 对齐参考实现 load()（117–122 行）。name 只作
     * 注册表键查询，不解析成路径。
     */
    public String load(String name) {
        Skill skill = skills.get(name);

        if (skill != null) {
            return skill.content();
        }

        String available = String.join(", ", skills.keySet());
        return "Error: Unknown skill '" + name
                + "'. Available: "
                + (available.isEmpty() ? "none" : available);
    }
}
