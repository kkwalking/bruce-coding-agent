package com.brucecli.plan.model;

import com.brucecli.plan.util.DagValidator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 一次 Plan-and-Execute 的完整计划。
 *
 * <p>这版更贴近原文结构：
 * id 表示计划 ID，goal 表示用户目标，tasks 是 DAG 节点，
 * executionOrder 是拓扑排序后的线性执行顺序，status 是整张计划的状态。</p>
 */
public class ExecutionPlan {
    private final String id;
    private final String goal;
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final List<String> executionOrder = new ArrayList<>();
    private PlanStatus status = PlanStatus.CREATED;
    private String summary;
    private long startTime;
    private long endTime;

    public ExecutionPlan(String goal) {
        this("plan_" + UUID.randomUUID().toString().substring(0, 8), goal);
    }

    public ExecutionPlan(String id, String goal) {
        this.id = id;
        this.goal = goal;
    }

    public String id() {
        return id;
    }

    public String goal() {
        return goal;
    }

    public PlanStatus status() {
        return status;
    }

    public String summary() {
        return summary;
    }

    public ExecutionPlan summary(String summary) {
        this.summary = summary;
        return this;
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

    public Map<String, Task> taskMap() {
        return Map.copyOf(tasks);
    }

    public List<Task> tasks() {
        return List.copyOf(tasks.values());
    }

    public Task task(String id) {
        return tasks.get(id);
    }

    public ExecutionPlan addTask(Task task) {
        if (tasks.containsKey(task.id())) {
            throw new IllegalArgumentException("重复任务 ID: " + task.id());
        }
        tasks.put(task.id(), task);
        return this;
    }

    /**
     * 添加 DAG 边：taskId 依赖 dependencyId。
     */
    public ExecutionPlan addDependency(String taskId, String dependencyId) {
        Task task = requireTask(taskId);
        requireTask(dependencyId);
        task.addDependency(dependencyId);
        return this;
    }

    /**
     * 原文中的 computeExecutionOrder：使用 DFS 拓扑排序把 DAG 转成线性执行顺序。
     *
     * <p>visited 表示已经处理完的节点，visiting 表示当前 DFS 路径上的节点。
     * 如果再次遇到 visiting 中的节点，就说明 DAG 中存在环。</p>
     */
    public boolean computeExecutionOrder() {
        executionOrder.clear();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (Task task : tasks.values()) {
            if (!visited.contains(task.id())) {
                if (!topologicalSort(task, visited, visiting)) {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean topologicalSort(Task task, Set<String> visited, Set<String> visiting) {
        String taskId = task.id();
        if (visiting.contains(taskId)) {
            return false;
        }
        if (visited.contains(taskId)) {
            return true;
        }

        visiting.add(taskId);
        for (String dependencyId : task.dependencies()) {
            Task dependency = tasks.get(dependencyId);
            if (dependency == null) {
                throw new IllegalStateException("任务 " + taskId + " 依赖不存在的任务: " + dependencyId);
            }
            if (!topologicalSort(dependency, visited, visiting)) {
                return false;
            }
        }

        visiting.remove(taskId);
        visited.add(taskId);
        executionOrder.add(taskId);
        return true;
    }

    /**
     * 返回当前这一轮可执行的任务集合。
     *
     * <p>这些任务之间没有未完成依赖，因此可以串行执行，也可以并行执行。</p>
     */
    public List<Task> executableTasks() {
        Map<String, Task> map = taskMap();
        return tasks.values().stream()
            .filter(task -> task.isExecutable(map))
            .toList();
    }

    public boolean hasPendingTasks() {
        return tasks.values().stream().anyMatch(task -> task.status() == TaskStatus.PENDING);
    }

    /**
     * 把依赖已经失败的任务标记为 SKIPPED。
     *
     * <p>这里使用循环传播，而不是只扫一遍：
     * 如果 t3 依赖 t2，t2 又依赖失败的 t1，那么 t2 被跳过后，t3 也应该在同一次调用里被跳过。
     * 每一轮都会基于最新的任务状态重新判断，直到没有新的任务被跳过。</p>
     */
    public List<Task> skipBlockedTasks() {
        List<Task> skipped = new ArrayList<>();

        boolean changed;
        do {
            changed = false;
            Map<String, Task> map = taskMap();
            for (Task task : tasks.values()) {
                if (task.status() == TaskStatus.PENDING && task.hasBrokenDependency(map)) {
                    task.markSkipped("依赖任务失败或被跳过");
                    skipped.add(task);
                    changed = true;
                }
            }
        } while (changed);

        return skipped;
    }

    public void markStarted() {
        this.status = PlanStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
    }

    public void markCompleted() {
        this.status = PlanStatus.COMPLETED;
        this.endTime = System.currentTimeMillis();
    }

    public void markFailed(String reason) {
        this.status = PlanStatus.FAILED;
        this.summary = reason;
        this.endTime = System.currentTimeMillis();
    }

    public boolean allTasksCompleted() {
        return !tasks.isEmpty() && tasks.values().stream().allMatch(task -> task.status() == TaskStatus.COMPLETED);
    }

    /**
     * 检查计划是否是合法 DAG。
     */
    public void validateDag() {
        DagValidator.validate(this);
    }

    /**
     * 拓扑排序后的任务顺序，可用于执行前预览。
     */
    public List<Task> topologicalOrder() {
        return DagValidator.topologicalOrder(this);
    }

    /**
     * 计划可视化：用文本方式展示 DAG 中每个任务和依赖。
     */
    public String visualize() {
        StringBuilder builder = new StringBuilder();
        builder.append("执行计划: ").append(goal).append('\n');
        if (summary != null && !summary.isBlank()) {
            builder.append("摘要: ").append(summary).append('\n');
        }
        builder.append("状态: ").append(status).append('\n');
        builder.append("任务列表:\n");
        for (Task task : tasks.values()) {
            builder.append("- ")
                .append(task.id())
                .append(" [").append(task.type()).append("] ")
                .append(task.description())
                .append(" deps=").append(task.dependencies())
                .append(" status=").append(task.status())
                .append('\n');
        }
        if (!executionOrder.isEmpty()) {
            builder.append("执行顺序: ").append(String.join(" -> ", executionOrder)).append('\n');
        }
        return builder.toString();
    }

    private Task requireTask(String id) {
        Task task = tasks.get(id);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + id);
        }
        return task;
    }
}
