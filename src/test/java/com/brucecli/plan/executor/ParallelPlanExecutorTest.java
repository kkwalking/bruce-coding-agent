package com.brucecli.plan.executor;

import com.brucecli.runtime.ConcurrencyConfig;
import com.brucecli.plan.executor.PlanExecutionReport;
import com.brucecli.plan.model.ExecutionPlan;
import com.brucecli.plan.model.Task;
import com.brucecli.plan.model.TaskStatus;
import com.brucecli.plan.model.TaskType;
import com.brucecli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelPlanExecutorTest {
    @TempDir
    Path tempDir;

    @Test
    void executesDagBatchInParallel() {
        ExecutionPlan plan = new ExecutionPlan("并行分析");
        plan.addTask(new Task("t1", "获取用户数据", TaskType.COMMAND).command("sleep:300:user"));
        plan.addTask(new Task("t2", "获取订单数据", TaskType.COMMAND).command("sleep:300:order"));
        plan.addTask(new Task("t3", "合并分析", TaskType.ANALYSIS).addDependency("t1").addDependency("t2"));

        ParallelPlanExecutor executor = new ParallelPlanExecutor(
            new FakeToolRegistry(tempDir),
            new ConcurrencyConfig(2, Duration.ofSeconds(2), 2_000)
        );
        long start = System.currentTimeMillis();
        PlanExecutionReport report = executor.execute(plan);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(report.success());
        assertTrue(elapsed < 560, "第一批两个 300ms 任务应并行执行: " + elapsed);
        assertEquals(TaskStatus.COMPLETED, plan.task("t3").status());
    }

    @Test
    void safeGuardResultMarksTaskFailed() {
        ExecutionPlan plan = new ExecutionPlan("拒绝危险命令");
        plan.addTask(new Task("t1", "危险命令", TaskType.COMMAND).command("deny"));
        ParallelPlanExecutor executor = new ParallelPlanExecutor(
            new FakeToolRegistry(tempDir),
            new ConcurrencyConfig(2, Duration.ofSeconds(2), 2_000)
        );

        executor.execute(plan);

        assertEquals(TaskStatus.FAILED, plan.task("t1").status());
        assertTrue(plan.task("t1").error().contains("命令被安全策略拒绝"));
    }

    private static class FakeToolRegistry extends ToolRegistry {
        FakeToolRegistry(Path workspaceRoot) {
            super(workspaceRoot);
        }

        @Override
        public String executeTool(String name, Map<String, String> args) {
            if ("execute_command".equals(name)) {
                String command = args.get("command");
                if ("deny".equals(command)) {
                    return "命令被安全策略拒绝: test";
                }
                if (command != null && command.startsWith("sleep:")) {
                    String[] parts = command.split(":");
                    try {
                        Thread.sleep(Long.parseLong(parts[1]));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return "interrupted";
                    }
                    return parts[2] + " done";
                }
            }
            return super.executeTool(name, args);
        }
    }
}
