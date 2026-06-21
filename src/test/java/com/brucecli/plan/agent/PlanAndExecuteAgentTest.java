package com.brucecli.plan.agent;

import com.brucecli.plan.executor.PlanExecutionReport;
import com.brucecli.plan.executor.PlanExecutor;
import com.brucecli.plan.model.ExecutionPlan;
import com.brucecli.plan.model.Task;
import com.brucecli.plan.model.TaskStatus;
import com.brucecli.plan.model.TaskType;
import com.brucecli.plan.planner.Planner;
import com.brucecli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanAndExecuteAgentTest {
    @TempDir
    Path tempDir;

    @Test
    void plansOnceThenExecutesDagTasks() throws Exception {
        Planner planner = userGoal -> new ExecutionPlan(userGoal)
            .addTask(new Task("t1", "写入 Hello.java", TaskType.FILE_WRITE)
                .path("demo/Hello.java")
                .content("""
                    public class Hello {
                        public static void main(String[] args) {
                            System.out.println("Hello BruceAgent");
                        }
                    }
                    """))
            .addTask(new Task("t2", "编译 Hello.java", TaskType.COMMAND)
                .command("javac demo/Hello.java"))
            .addTask(new Task("t3", "运行 Hello.java", TaskType.VERIFICATION)
                .command("java -cp demo Hello"))
            .addDependency("t2", "t1")
            .addDependency("t3", "t2");

        PlanAndExecuteAgent agent = new PlanAndExecuteAgent(
            planner,
            new PlanExecutor(new ToolRegistry(tempDir))
        );

        PlanExecutionReport report = agent.run("创建并运行 Hello", "");

        assertTrue(report.success());
        assertEquals(TaskStatus.COMPLETED, report.plan().task("t3").status());
        assertTrue(report.plan().task("t3").result().contains("Hello BruceAgent"));
        assertTrue(Files.exists(tempDir.resolve("demo/Hello.class")));
    }

    @Test
    void replansWhenMostTasksFail() throws Exception {
        class ReplanningPlanner implements Planner {
            private int replanCount;

            @Override
            public ExecutionPlan plan(String userGoal) {
                return new ExecutionPlan(userGoal)
                    .addTask(new Task("t1", "读取不存在的文件", TaskType.FILE_READ).path("missing.txt"));
            }

            @Override
            public ExecutionPlan replan(ExecutionPlan failedPlan, String failureReason) throws IOException {
                replanCount++;
                return new ExecutionPlan(failedPlan.goal())
                    .addTask(new Task("t1", "写入补救文件", TaskType.FILE_WRITE)
                        .path("fixed.txt")
                        .content("replanned"));
            }
        }

        ReplanningPlanner planner = new ReplanningPlanner();
        PlanAndExecuteAgent agent = new PlanAndExecuteAgent(
            planner,
            new PlanExecutor(new ToolRegistry(tempDir))
        );

        PlanExecutionReport report = agent.run("读取文件，如果失败就重建", "");

        assertTrue(report.success());
        assertEquals(1, planner.replanCount);
        assertEquals("replanned", Files.readString(tempDir.resolve("fixed.txt")));
    }

    @Test
    void passesSupplementalContextToPlannerWithoutChangingGoal() throws Exception {
        AtomicReference<String> capturedGoal = new AtomicReference<>();
        AtomicReference<String> capturedContext = new AtomicReference<>();
        Planner planner = new Planner() {
            @Override
            public ExecutionPlan plan(String userGoal) {
                return plan(userGoal, "");
            }

            @Override
            public ExecutionPlan plan(String userGoal, String supplementalContext) {
                capturedGoal.set(userGoal);
                capturedContext.set(supplementalContext);
                return new ExecutionPlan(userGoal)
                    .addTask(new Task("t1", "分析上下文", TaskType.ANALYSIS));
            }
        };
        PlanAndExecuteAgent agent = new PlanAndExecuteAgent(
            planner,
            new PlanExecutor(new ToolRegistry(tempDir))
        );

        PlanExecutionReport report = agent.run("定位登录实现", "RAG: LoginService.java");

        assertTrue(report.success());
        assertEquals("定位登录实现", capturedGoal.get());
        assertEquals("RAG: LoginService.java", capturedContext.get());
        assertEquals("定位登录实现", report.plan().goal());
    }

    @Test
    void hitlRejectionIsNotMarkedAsCompleted() throws Exception {
        Planner planner = userGoal -> new ExecutionPlan(userGoal)
            .addTask(new Task("t1", "写入文件", TaskType.FILE_WRITE)
                .path("blocked.txt")
                .content("blocked"));
        ToolRegistry rejectingRegistry = new ToolRegistry(tempDir) {
            @Override
            public String executeTool(String name, Map<String, String> args) {
                return "[HITL] 操作已被拒绝：用户拒绝";
            }
        };
        PlanAndExecuteAgent agent = new PlanAndExecuteAgent(
            planner,
            new PlanExecutor(rejectingRegistry)
        );

        PlanExecutionReport report = agent.run("写入文件", "");

        assertTrue(!report.success());
        assertEquals(TaskStatus.FAILED, report.plan().task("t1").status());
        assertTrue(report.plan().task("t1").error().contains("HITL"));
    }
}
