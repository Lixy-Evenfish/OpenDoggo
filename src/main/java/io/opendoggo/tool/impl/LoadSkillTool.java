package io.opendoggo.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.opendoggo.skill.SkillLoader;
import io.opendoggo.tool.ToolHandler;

/**
 * s07 第八个工具：按名称取回完整 SKILL.md，
 * 对应参考实现 references/code.py 的 load_skill
 * （213–214 行 schema、223 行 SKILL_LOADER.load 绑定）。
 *
 * name 只作 SkillLoader 注册表键查询，不解析成路径——
 * 没有路径穿越面，因此不注册权限规则
 * （fail-open，同 todo_write/task）。
 * 未命中返回错误字符串而不是异常（错误回传给模型）。
 */
public class LoadSkillTool implements ToolHandler {

    private final SkillLoader skillLoader;

    public LoadSkillTool(SkillLoader skillLoader) {
        this.skillLoader = skillLoader;
    }

    @Override
    public String name() {
        return "load_skill";
    }

    @Override
    public String description() {
        return "Load the full SKILL.md content by skill name.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");

        schema.putObject("properties")
                .putObject("name")
                .put("type", "string");

        schema.putArray("required").add("name");
        return schema;
    }

    @Override
    public String execute(JsonNode input) {
        String name = input == null
                ? null
                : input.path("name").asText(null);

        if (name == null || name.isBlank()) {
            return "Error: name cannot be empty";
        }

        return skillLoader.load(name);
    }
}
