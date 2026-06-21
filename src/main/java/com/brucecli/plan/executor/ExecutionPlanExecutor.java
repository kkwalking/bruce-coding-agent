package com.brucecli.plan.executor;

import com.brucecli.plan.model.ExecutionPlan;

/**
 * Plan-and-Execute 执行阶段的统一接口。
 */
@FunctionalInterface
public interface ExecutionPlanExecutor {
    PlanExecutionReport execute(ExecutionPlan plan);
}
