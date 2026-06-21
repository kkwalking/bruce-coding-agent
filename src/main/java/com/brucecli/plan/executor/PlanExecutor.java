package com.brucecli.plan.executor;

import com.brucecli.plan.model.ExecutionPlan;
import com.brucecli.plan.model.Task;
import com.brucecli.plan.model.TaskStatus;
import com.brucecli.plan.model.TaskType;
import com.brucecli.tool.ToolRegistry;

import java.util.List;
import java.util.Map;

/**
 * DAG 计划执行器。
 *
 * <p>它不再调用 LLM，而是不断做三件事：
 * 1. 找出所有依赖已完成的 PENDING 任务；
 * 2. 按任务类型调用本地工具；
 * 3. 更新状态，并让下一批任务变成可执行。</p>
 */
public class PlanExecutor implements ExecutionPlanExecutor {
    private final ToolRegistry toolRegistry;

    public PlanExecutor(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
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
                // 如果没有可执行任务但仍有 PENDING，说明存在缺失依赖或不可达状态。
                plan.tasks().stream()
                    .filter(task -> task.status() == TaskStatus.PENDING)
                    .forEach(task -> task.markSkipped("没有可执行依赖路径"));
                break;
            }

            for (Task task : executableTasks) {
                executeOne(task);
            }
        }

        if (plan.allTasksCompleted()) {
            plan.markCompleted();
        } else {
            plan.markFailed("计划执行完成，但存在失败或跳过任务");
        }
        return new PlanExecutionReport(plan, startTime, System.currentTimeMillis());
    }

    private void executeOne(Task task) {
        task.markStarted();
        try {
            String result = switch (task.type()) {
                case PLANNING, ANALYSIS -> completeTextTask(task);
                case FILE_READ -> toolRegistry.executeTool("read_file", Map.of("path", require(task.path(), "path")));
                case FILE_WRITE -> toolRegistry.executeTool("write_file", Map.of(
                    "path", require(task.path(), "path"),
                    "content", task.content() == null ? "" : task.content()
                ));
                case COMMAND, VERIFICATION -> toolRegistry.executeTool(
                    "execute_command",
                    Map.of("command", require(task.command(), "command"))
                );
            };

            if (result.startsWith("[HITL] 操作已被跳过")) {
                task.markSkipped(result);
            } else if (result.startsWith("[HITL] 操作已被拒绝")
                || result.startsWith("工具执行失败")
                || result.startsWith("工具参数解析失败")) {
                task.markFailed(result);
            } else {
                task.markCompleted(result);
            }
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

    private String require(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(taskFieldMissing(fieldName));
        }
        return value;
    }

    private String taskFieldMissing(String fieldName) {
        return "任务缺少执行字段: " + fieldName;
    }
}
