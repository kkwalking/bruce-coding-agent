package com.brucecli.plan.planner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.brucecli.plan.model.ExecutionPlan;
import com.brucecli.plan.model.Task;
import com.brucecli.plan.model.TaskType;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 把 LLM 输出的 JSON 计划解析成 ExecutionPlan。
 *
 * <p>模型输出不一定总是纯 JSON，可能包在 ```json ... ``` 代码块里。
 * 这个解析器会先提取 JSON 对象，再按约定字段创建 Task 和依赖边。</p>
 */
public class PlanJsonParser {
    private final ObjectMapper mapper = new ObjectMapper();

    public ExecutionPlan parse(String rawText, String fallbackGoal) throws IOException {
        String json = extractJsonObject(rawText);
        JsonNode root = mapper.readTree(json);
        String goal = root.path("goal").asText(fallbackGoal);
        String summary = root.path("summary").asText("");
        ExecutionPlan plan = new ExecutionPlan(goal).summary(summary);

        JsonNode tasksNode = root.path("tasks");
        if (!tasksNode.isArray() || tasksNode.isEmpty()) {
            throw new IllegalArgumentException("计划 JSON 中必须包含非空 tasks 数组");
        }

        Map<String, String> idMapping = new HashMap<>();
        int taskIndex = 1;
        for (JsonNode taskNode : tasksNode) {
            String id = requiredText(taskNode, "id");
            String normalizedId = id.matches("task_\\d+") ? id : "task_" + taskIndex;
            idMapping.put(id, normalizedId);
            taskIndex++;

            String description = requiredText(taskNode, "description");
            TaskType type = TaskType.valueOf(requiredText(taskNode, "type"));
            Task task = new Task(normalizedId, description, type)
                .path(optionalText(taskNode, "path"))
                .content(optionalText(taskNode, "content"))
                .command(optionalText(taskNode, "command"));
            plan.addTask(task);
        }

        for (JsonNode taskNode : tasksNode) {
            String id = idMapping.get(requiredText(taskNode, "id"));
            JsonNode dependenciesNode = taskNode.path("dependencies");
            if (dependenciesNode.isArray()) {
                for (JsonNode dependencyNode : dependenciesNode) {
                    String mappedDependency = idMapping.get(dependencyNode.asText());
                    if (mappedDependency == null) {
                        throw new IllegalArgumentException("依赖不存在: " + dependencyNode.asText());
                    }
                    plan.addDependency(id, mappedDependency);
                }
            }
        }

        plan.validateDag();
        plan.computeExecutionOrder();
        return plan;
    }

    private String extractJsonObject(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("LLM 没有返回计划内容");
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
            throw new IllegalArgumentException("无法从 LLM 输出中提取 JSON 对象: " + rawText);
        }
        return text.substring(start, end + 1);
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("任务缺少必填字段: " + field);
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
