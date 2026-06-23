package com.brucecli.plan.agent;

import com.brucecli.plan.executor.ExecutionPlanExecutor;
import com.brucecli.plan.executor.PlanExecutionReport;
import com.brucecli.plan.model.ExecutionPlan;
import com.brucecli.plan.planner.Planner;
import com.brucecli.plan.util.PlanValidator;

import java.io.IOException;
import java.util.List;

/**
 * Plan-and-Execute Agent 门面。
 *
 * <p>它把两个阶段串起来：
 * Plan：调用 DeepSeekPlanner 生成 DAG；
 * Execute：调用 PlanExecutor 按 DAG 执行任务。</p>
 */
public class PlanAndExecuteAgent {
    private final Planner planner;
    private final ExecutionPlanExecutor executor;
    private final PlanValidator planValidator = new PlanValidator();

    public PlanAndExecuteAgent(Planner planner, ExecutionPlanExecutor executor) {
        this.planner = planner;
        this.executor = executor;
    }

    public PlanExecutionReport run(String userGoal, String supplementalContext) throws IOException {
        return run(userGoal, supplementalContext, "");
    }

    public PlanExecutionReport run(
        String userGoal,
        String supplementalContext,
        String taskSystemContext
    ) throws IOException {
        ExecutionPlan plan = planner.plan(userGoal, supplementalContext, taskSystemContext);
        List<String> errors = planValidator.validatePlan(plan);
        if (!errors.isEmpty()) {
            plan = planner.replan(
                plan,
                "计划校验失败: " + String.join("; ", errors),
                supplementalContext,
                taskSystemContext
            );
        }

        PlanExecutionReport report = executor.execute(plan);
        if (!report.success() && report.successRate() < 0.5) {
            // 对应文章“规划的自我修正”：失败较多时，把失败上下文交回 Planner，重新生成一版计划再执行。
            ExecutionPlan replanned = planner.replan(
                report.plan(),
                "执行成功率低于 50%，成功率=" + report.successRate(),
                supplementalContext,
                taskSystemContext
            );
            return executor.execute(replanned);
        }
        return report;
    }

}
