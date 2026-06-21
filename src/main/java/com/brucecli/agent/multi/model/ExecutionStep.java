package com.brucecli.agent.multi.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Multi-Agent 计划中的单个执行步骤。
 *
 * <p>它和 Plan DAG 模块里的 Task 类似，但多了 attempts 字段，
 * 用来记录 Reviewer 打回后 Worker 重新执行了几次。</p>
 */
public class ExecutionStep {
    private final String id;
    private final String description;
    private final StepType type;
    private final List<String> dependencies = new ArrayList<>();
    private StepStatus status = StepStatus.PENDING;
    private String result;
    private String error;
    private int attempts;
    private long startTime;
    private long endTime;

    public ExecutionStep(String id, String description, StepType type) {
        this.id = requireText(id, "id");
        this.description = requireText(description, "description");
        this.type = Objects.requireNonNullElse(type, StepType.GENERAL);
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    public StepType type() {
        return type;
    }

    public StepStatus status() {
        return status;
    }

    public String result() {
        return result;
    }

    public String error() {
        return error;
    }

    public int attempts() {
        return attempts;
    }

    public long startTime() {
        return startTime;
    }

    public long endTime() {
        return endTime;
    }

    public long durationMillis() {
        if (startTime == 0 || endTime == 0) {
            return 0;
        }
        return endTime - startTime;
    }

    public List<String> dependencies() {
        return List.copyOf(dependencies);
    }

    public ExecutionStep addDependency(String dependencyId) {
        if (dependencyId != null && !dependencyId.isBlank() && !dependencies.contains(dependencyId)) {
            dependencies.add(dependencyId);
        }
        return this;
    }

    public boolean isExecutable(Map<String, ExecutionStep> stepMap) {
        if (status != StepStatus.PENDING) {
            return false;
        }
        for (String dependencyId : dependencies) {
            ExecutionStep dependency = stepMap.get(dependencyId);
            if (dependency == null || dependency.status() != StepStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    public boolean hasBrokenDependency(Map<String, ExecutionStep> stepMap) {
        for (String dependencyId : dependencies) {
            ExecutionStep dependency = stepMap.get(dependencyId);
            if (dependency == null
                || dependency.status() == StepStatus.FAILED
                || dependency.status() == StepStatus.SKIPPED) {
                return true;
            }
        }
        return false;
    }

    public void markStarted() {
        status = StepStatus.RUNNING;
        attempts++;
        if (startTime == 0) {
            startTime = System.currentTimeMillis();
        }
    }

    public void markRetryable(String feedback) {
        status = StepStatus.PENDING;
        error = feedback;
    }

    public void markCompleted(String result) {
        status = StepStatus.COMPLETED;
        this.result = result;
        this.error = null;
        this.endTime = System.currentTimeMillis();
    }

    public void markFailed(String error) {
        status = StepStatus.FAILED;
        this.error = error;
        this.endTime = System.currentTimeMillis();
    }

    public void markSkipped(String reason) {
        status = StepStatus.SKIPPED;
        this.error = reason;
        this.endTime = System.currentTimeMillis();
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value;
    }
}
