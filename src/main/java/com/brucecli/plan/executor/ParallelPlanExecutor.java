package com.brucecli.plan.executor;

import com.brucecli.runtime.ConcurrencyConfig;
import com.brucecli.runtime.DaemonThreadFactory;
import com.brucecli.plan.executor.ExecutionPlanExecutor;
import com.brucecli.plan.executor.PlanExecutionReport;
import com.brucecli.plan.model.ExecutionPlan;
import com.brucecli.plan.model.Task;
import com.brucecli.plan.model.TaskStatus;
import com.brucecli.plan.model.TaskType;
import com.brucecli.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Plan-and-Execute 的 DAG 批次并行执行器。
 */
public class ParallelPlanExecutor implements ExecutionPlanExecutor {
    private final ToolRegistry toolRegistry;
    private final ConcurrencyConfig config;

    public ParallelPlanExecutor(ToolRegistry toolRegistry, ConcurrencyConfig config) {
        this.toolRegistry = toolRegistry;
        this.config = config;
    }

    @Override
    public PlanExecutionReport execute(ExecutionPlan plan) {
        long startTime = System.currentTimeMillis();
        plan.validateDag();
        plan.computeExecutionOrder();
        plan.markStarted();

        while (plan.hasPendingTasks()) {
            plan.skipBlockedTasks();
            List<Task> executableTasks = plan.executableTasks();
            if (executableTasks.isEmpty()) {
                plan.tasks().stream()
                    .filter(task -> task.status() == TaskStatus.PENDING)
                    .forEach(task -> task.markSkipped("没有可执行依赖路径"));
                break;
            }
            executeTaskBatch(plan, executableTasks);
        }

        if (plan.allTasksCompleted()) {
            plan.markCompleted();
        } else {
            plan.markFailed("计划执行完成，但存在失败或跳过任务");
        }
        return new PlanExecutionReport(plan, startTime, System.currentTimeMillis());
    }

    /**
     * 文章里的核心方法：同一批 executableTasks 没有未完成依赖，可以并行执行。
     */
    public List<TaskExecutionResult> executeTaskBatch(ExecutionPlan plan, List<Task> executableTasks) {
        if (executableTasks == null || executableTasks.isEmpty()) {
            return List.of();
        }
        if (executableTasks.size() == 1) {
            Task task = executableTasks.get(0);
            executeOne(task);
            return List.of(TaskExecutionResult.from(task, false));
        }

        int parallelism = config.parallelismFor(executableTasks.size());
        ExecutorService executor = Executors.newFixedThreadPool(
            parallelism,
            new DaemonThreadFactory("bruce-coding-agent-plan")
        );
        try {
            List<Callable<TaskExecutionResult>> callables = new ArrayList<>();
            for (Task task : executableTasks) {
                callables.add(() -> {
                    executeOne(task);
                    return TaskExecutionResult.from(task, false);
                });
            }

            List<Future<TaskExecutionResult>> futures = executor.invokeAll(
                callables,
                config.batchTimeout().toMillis(),
                TimeUnit.MILLISECONDS
            );

            List<TaskExecutionResult> results = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                Task task = executableTasks.get(i);
                Future<TaskExecutionResult> future = futures.get(i);
                if (future.isCancelled()) {
                    task.markFailed("任务执行超时，已取消: " + task.id());
                    results.add(TaskExecutionResult.from(task, true));
                    continue;
                }
                try {
                    results.add(future.get());
                } catch (Exception e) {
                    task.markFailed("任务执行失败: " + e.getMessage());
                    results.add(TaskExecutionResult.from(task, false));
                }
            }

            return results.stream()
                .sorted(Comparator.comparing(result -> indexOf(executableTasks, result.taskId())))
                .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return markInterrupted(executableTasks);
        } finally {
            executor.shutdownNow();
        }
    }

    private int indexOf(List<Task> tasks, String taskId) {
        for (int i = 0; i < tasks.size(); i++) {
            if (tasks.get(i).id().equals(taskId)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private List<TaskExecutionResult> markInterrupted(List<Task> tasks) {
        List<TaskExecutionResult> results = new ArrayList<>();
        for (Task task : tasks) {
            if (task.status() == TaskStatus.PENDING || task.status() == TaskStatus.RUNNING) {
                task.markFailed("任务批次执行被中断");
            }
            results.add(TaskExecutionResult.from(task, false));
        }
        return results;
    }

    private void executeOne(Task task) {
        task.markStarted();
        try {
            String result = switch (task.type()) {
                case PLANNING, ANALYSIS -> completeTextTask(task);
                case FILE_READ -> toolRegistry.executeTool("read_file", Map.of(
                    "path", require(task.path(), "path")
                ));
                case FILE_WRITE -> toolRegistry.executeTool("write_file", Map.of(
                    "path", require(task.path(), "path"),
                    "content", task.content() == null ? "" : task.content()
                ));
                case COMMAND, VERIFICATION -> toolRegistry.executeTool("execute_command", Map.of(
                    "command", require(task.command(), "command")
                ));
            };
            markByToolResult(task, result);
        } catch (Exception e) {
            task.markFailed(e.getMessage());
        }
    }

    private String completeTextTask(Task task) {
        if (task.command() != null && !task.command().isBlank()) {
            return toolRegistry.executeTool("execute_command", Map.of("command", task.command()));
        }
        return "已完成: " + task.description();
    }

    private void markByToolResult(Task task, String result) {
        if (result.startsWith("[HITL] 操作已被跳过")) {
            task.markSkipped(result);
        } else if (result.startsWith("[HITL] 操作已被拒绝")
            || result.startsWith("工具执行失败")
            || result.startsWith("工具参数解析失败")
            || result.startsWith("命令被安全策略拒绝")) {
            task.markFailed(result);
        } else {
            task.markCompleted(result);
        }
    }

    private String require(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("任务缺少执行字段: " + fieldName);
        }
        return value;
    }
}
