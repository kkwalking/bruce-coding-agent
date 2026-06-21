package com.brucecli.plan.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 文章中的 Task 类设计。
 *
 * <p>Task 是 Plan-and-Execute 的最小执行单元：
 * 规划阶段只生成一组 Task 和依赖关系；执行阶段不再反复询问 LLM，
 * 而是根据 Task 的 type、dependencies、status 来调度。</p>
 */
public class Task {
    private final String id;
    private final String description;
    private final TaskType type;
    private TaskStatus status = TaskStatus.PENDING;
    private String result;
    private String error;
    private final List<String> dependencies = new ArrayList<>();
    private long startTime;
    private long endTime;

    /**
     * FILE_READ/FILE_WRITE 使用的路径。
     */
    private String path;

    /**
     * FILE_WRITE 使用的文件内容。
     */
    private String content;

    /**
     * COMMAND/VERIFICATION 使用的 Shell 命令。
     */
    private String command;

    public Task(String id, String description, TaskType type) {
        this.id = requireText(id, "id");
        this.description = requireText(description, "description");
        this.type = Objects.requireNonNull(type, "type");
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    public TaskType type() {
        return type;
    }

    public TaskStatus status() {
        return status;
    }

    public String result() {
        return result;
    }

    public String error() {
        return error;
    }

    public List<String> dependencies() {
        return List.copyOf(dependencies);
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

    public String path() {
        return path;
    }

    public String content() {
        return content;
    }

    public String command() {
        return command;
    }

    public Task path(String path) {
        this.path = path;
        return this;
    }

    public Task content(String content) {
        this.content = content;
        return this;
    }

    public Task command(String command) {
        this.command = command;
        return this;
    }

    /**
     * 添加依赖：当前任务必须等 dependencyId 对应的任务完成后才能执行。
     */
    public Task addDependency(String dependencyId) {
        if (dependencyId != null && !dependencyId.isBlank() && !dependencies.contains(dependencyId)) {
            dependencies.add(dependencyId);
        }
        return this;
    }

    /**
     * 文章中提到的核心方法：判断任务当前是否可执行。
     *
     * <p>一个任务可执行需要同时满足：
     * 1. 自己仍然是 PENDING；
     * 2. 所有依赖任务都存在；
     * 3. 所有依赖任务都已经 COMPLETED。</p>
     */
    public boolean isExecutable(Map<String, Task> taskMap) {
        if (status != TaskStatus.PENDING) {
            return false;
        }
        for (String dependencyId : dependencies) {
            Task dependency = taskMap.get(dependencyId);
            if (dependency == null || dependency.status() != TaskStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    /**
     * 如果任一依赖失败或被跳过，那么当前任务永远无法满足依赖，应该被标记为 SKIPPED。
     */
    public boolean hasBrokenDependency(Map<String, Task> taskMap) {
        for (String dependencyId : dependencies) {
            Task dependency = taskMap.get(dependencyId);
            if (dependency == null
                || dependency.status() == TaskStatus.FAILED
                || dependency.status() == TaskStatus.SKIPPED) {
                return true;
            }
        }
        return false;
    }

    public void markStarted() {
        this.status = TaskStatus.RUNNING;
        this.startTime = System.currentTimeMillis();
    }

    public void markCompleted(String result) {
        this.status = TaskStatus.COMPLETED;
        this.result = result;
        this.endTime = System.currentTimeMillis();
    }

    public void markFailed(String error) {
        this.status = TaskStatus.FAILED;
        this.error = error;
        this.endTime = System.currentTimeMillis();
    }

    public void markSkipped(String reason) {
        this.status = TaskStatus.SKIPPED;
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
