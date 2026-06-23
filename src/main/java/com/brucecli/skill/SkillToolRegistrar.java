package com.brucecli.skill;

import com.brucecli.tool.Tool;
import com.brucecli.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public final class SkillToolRegistrar {
    public static final String LOAD_TOOL_NAME = "load_skill";
    public static final String RESOURCE_TOOL_NAME = "read_skill_resource";

    private SkillToolRegistrar() {
    }

    public static boolean isSkillTool(String name) {
        return LOAD_TOOL_NAME.equals(name) || RESOURCE_TOOL_NAME.equals(name);
    }

    public static void register(ToolRegistry registry, SkillManager manager) {
        ObjectNode loadParameters = new ObjectMapper().createObjectNode();
        loadParameters.put("type", "object");
        ObjectNode loadProperties = loadParameters.putObject("properties");
        loadProperties.putObject("name")
            .put("type", "string")
            .put("description", "要加载的 Skill name，必须来自当前任务提供的 Skill 目录");
        loadParameters.putArray("required").add("name");
        registry.register(new Tool(
            LOAD_TOOL_NAME,
            "加载一个 Skill 的完整工作流指令；仅当用户任务与 Skill 描述匹配时调用",
            loadParameters,
            args -> manager.loadSkill(args.get("name"))
        ));

        ObjectNode parameters = new ObjectMapper().createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        properties.putObject("skill")
            .put("type", "string")
            .put("description", "已激活 Skill 的 name");
        properties.putObject("path")
            .put("type", "string")
            .put("description", "Skill 目录内的相对资源路径");
        ArrayNode required = parameters.putArray("required");
        required.add("skill");
        required.add("path");

        registry.register(new Tool(
            RESOURCE_TOOL_NAME,
            "读取当前任务已加载 Skill 目录内的只读资源文件；必须先调用 load_skill",
            parameters,
            args -> manager.readResource(args.get("skill"), args.get("path"))
        ));
    }
}
