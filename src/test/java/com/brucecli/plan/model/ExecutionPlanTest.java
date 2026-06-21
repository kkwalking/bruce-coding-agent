package com.brucecli.plan.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionPlanTest {
    @Test
    void executableTaskRequiresCompletedDependencies() {
        Task create = new Task("t1", "创建项目", TaskType.COMMAND);
        Task test = new Task("t2", "运行测试", TaskType.VERIFICATION);
        ExecutionPlan plan = new ExecutionPlan("创建并测试项目")
            .addTask(create)
            .addTask(test)
            .addDependency("t2", "t1");

        assertTrue(create.isExecutable(plan.taskMap()));
        assertFalse(test.isExecutable(plan.taskMap()));

        create.markStarted();
        create.markCompleted("ok");

        assertTrue(test.isExecutable(plan.taskMap()));
    }

    @Test
    void topologicalOrderPutsDependenciesBeforeDependents() {
        ExecutionPlan plan = new ExecutionPlan("DAG 排序")
            .addTask(new Task("t1", "创建项目", TaskType.COMMAND))
            .addTask(new Task("t2", "写代码", TaskType.FILE_WRITE))
            .addTask(new Task("t3", "运行测试", TaskType.VERIFICATION))
            .addDependency("t2", "t1")
            .addDependency("t3", "t2");

        List<String> order = plan.topologicalOrder().stream().map(Task::id).toList();

        assertEquals(List.of("t1", "t2", "t3"), order);
    }

    @Test
    void detectsCycle() {
        ExecutionPlan plan = new ExecutionPlan("错误 DAG")
            .addTask(new Task("t1", "A", TaskType.ANALYSIS))
            .addTask(new Task("t2", "B", TaskType.ANALYSIS))
            .addDependency("t1", "t2")
            .addDependency("t2", "t1");

        assertThrows(IllegalStateException.class, plan::validateDag);
    }

    @Test
    void skipBlockedTasksPropagatesThroughDependencyChain() {
        Task root = new Task("t1", "失败的根任务", TaskType.COMMAND);
        root.markStarted();
        root.markFailed("boom");

        ExecutionPlan plan = new ExecutionPlan("跳过间接阻塞任务")
            // 故意先添加 t3，再添加 t2，模拟不利遍历顺序。
            .addTask(new Task("t3", "间接依赖失败任务", TaskType.VERIFICATION))
            .addTask(new Task("t2", "直接依赖失败任务", TaskType.COMMAND))
            .addTask(root)
            .addDependency("t2", "t1")
            .addDependency("t3", "t2");

        List<Task> skipped = plan.skipBlockedTasks();

        assertEquals(2, skipped.size());
        assertEquals(TaskStatus.SKIPPED, plan.task("t2").status());
        assertEquals(TaskStatus.SKIPPED, plan.task("t3").status());
    }
}
