package com.brucecli.plan.executor;

import com.brucecli.plan.model.Task;
import com.brucecli.plan.model.TaskStatus;

/**
 * DAG 中一个任务的执行结果快照。
 */
public record TaskExecutionResult(
    String taskId,
    TaskStatus status,
    String output,
    long durationMillis,
    boolean timedOut
) {
    public static TaskExecutionResult from(Task task, boolean timedOut) {
        String output = task.status() == TaskStatus.COMPLETED ? task.result() : task.error();
        return new TaskExecutionResult(
            task.id(),
            task.status(),
            output == null ? "" : output,
            task.durationMillis(),
            timedOut
        );
    }
}
