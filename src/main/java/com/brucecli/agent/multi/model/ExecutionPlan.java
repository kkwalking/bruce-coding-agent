package com.brucecli.agent.multi.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Planner 生成的执行计划。
 *
 * <p>编排器每轮调用 getExecutableSteps() 找出状态为 PENDING
 * 且所有依赖都 COMPLETED 的步骤，这些步骤可以并发分配给 Worker。</p>
 */
public class ExecutionPlan {
    private final String goal;
    private final Map<String, ExecutionStep> steps = new LinkedHashMap<>();

    public ExecutionPlan(String goal) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal 不能为空");
        }
        this.goal = goal;
    }

    public String goal() {
        return goal;
    }

    public ExecutionPlan addStep(ExecutionStep step) {
        if (steps.containsKey(step.id())) {
            throw new IllegalArgumentException("重复步骤 ID: " + step.id());
        }
        steps.put(step.id(), step);
        return this;
    }

    public ExecutionPlan addDependency(String stepId, String dependencyId) {
        ExecutionStep step = requireStep(stepId);
        requireStep(dependencyId);
        step.addDependency(dependencyId);
        return this;
    }

    public List<ExecutionStep> steps() {
        return List.copyOf(steps.values());
    }

    public Map<String, ExecutionStep> stepMap() {
        return Map.copyOf(steps);
    }

    public List<ExecutionStep> getExecutableSteps() {
        Map<String, ExecutionStep> map = stepMap();
        return steps.values().stream()
            .filter(step -> step.isExecutable(map))
            .toList();
    }

    public boolean hasPendingSteps() {
        return steps.values().stream().anyMatch(step -> step.status() == StepStatus.PENDING);
    }

    public boolean allStepsCompleted() {
        return !steps.isEmpty() && steps.values().stream().allMatch(step -> step.status() == StepStatus.COMPLETED);
    }

    public List<ExecutionStep> skipBlockedSteps() {
        List<ExecutionStep> skipped = new ArrayList<>();
        boolean changed;
        do {
            changed = false;
            Map<String, ExecutionStep> map = stepMap();
            for (ExecutionStep step : steps.values()) {
                if (step.status() == StepStatus.PENDING && step.hasBrokenDependency(map)) {
                    step.markSkipped("依赖步骤失败或被跳过");
                    skipped.add(step);
                    changed = true;
                }
            }
        } while (changed);
        return skipped;
    }

    public String completedContext() {
        StringBuilder builder = new StringBuilder();
        for (ExecutionStep step : steps.values()) {
            if (step.status() == StepStatus.COMPLETED) {
                builder.append("- ")
                    .append(step.id())
                    .append(": ")
                    .append(step.description())
                    .append('\n')
                    .append("  结果: ")
                    .append(abbreviate(step.result(), 1200))
                    .append('\n');
            }
        }
        return builder.toString();
    }

    private ExecutionStep requireStep(String id) {
        ExecutionStep step = steps.get(id);
        if (step == null) {
            throw new IllegalArgumentException("步骤不存在: " + id);
        }
        return step;
    }

    private static String abbreviate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars) + "...";
    }
}
