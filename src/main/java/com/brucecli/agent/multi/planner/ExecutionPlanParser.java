package com.brucecli.agent.multi.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.brucecli.agent.multi.model.ExecutionPlan;
import com.brucecli.agent.multi.model.ExecutionStep;
import com.brucecli.agent.multi.model.StepType;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把 Planner 的 JSON 文本解析成可调度的执行计划。
 *
 * <p>模型输出可能被包在 Markdown 代码块中，这里先提取 JSON 对象，
 * 再读取 goal/steps/dependencies。字段 steps 和 tasks 都兼容，方便对比
 * Plan DAG 模块和本篇 Multi-Agent 模块。</p>
 */
public class ExecutionPlanParser {
    private final ObjectMapper mapper = new ObjectMapper();

    public ExecutionPlan parse(String rawText, String fallbackGoal) throws IOException {
        String json = extractJsonObject(rawText);
        JsonNode root = mapper.readTree(json);
        String goal = optionalText(root, "goal");
        if (goal == null || goal.isBlank()) {
            goal = fallbackGoal;
        }

        ExecutionPlan plan = new ExecutionPlan(goal);
        JsonNode stepsNode = root.path("steps");
        if (!stepsNode.isArray()) {
            stepsNode = root.path("tasks");
        }
        if (!stepsNode.isArray() || stepsNode.isEmpty()) {
            throw new IllegalArgumentException("计划 JSON 中必须包含非空 steps 数组");
        }

        Map<String, String> idMapping = new LinkedHashMap<>();
        int index = 1;
        for (JsonNode stepNode : stepsNode) {
            String rawId = optionalText(stepNode, "id");
            String id = rawId == null || rawId.isBlank() ? "step_" + index : rawId.trim();
            idMapping.put(id, id);

            String description = requiredText(stepNode, "description");
            StepType type = StepType.from(optionalText(stepNode, "type"));
            plan.addStep(new ExecutionStep(id, description, type));
            index++;
        }

        for (JsonNode stepNode : stepsNode) {
            String stepId = optionalText(stepNode, "id");
            if (stepId == null || stepId.isBlank()) {
                continue;
            }
            JsonNode dependenciesNode = stepNode.path("dependencies");
            if (!dependenciesNode.isArray()) {
                continue;
            }
            for (JsonNode dependencyNode : dependenciesNode) {
                String dependencyId = dependencyNode.asText();
                if (!idMapping.containsKey(dependencyId)) {
                    throw new IllegalArgumentException("依赖不存在: " + dependencyId);
                }
                plan.addDependency(stepId, dependencyId);
            }
        }

        return plan;
    }

    public ExecutionPlan fallbackPlan(String goal) {
        return new ExecutionPlan(goal)
            .addStep(new ExecutionStep("step_1", goal, StepType.GENERAL));
    }

    private String extractJsonObject(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("Planner 没有返回计划内容");
        }

        String text = rawText.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).trim();
            }
        }

        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("无法从 Planner 输出中提取 JSON 对象: " + rawText);
        }
        return text.substring(start, end + 1);
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("步骤缺少必填字段: " + field);
        }
        return value;
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asText();
    }
}
