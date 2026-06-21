package com.brucecli.plan.planner;

import com.brucecli.plan.model.ExecutionPlan;

import java.io.IOException;

/**
 * 规划器接口。
 *
 * <p>Plan-and-Execute 中的 Plan 阶段只依赖这个接口。
 * 真实实现可以调用 DeepSeek，测试实现可以直接返回固定计划。</p>
 */
public interface Planner {
    ExecutionPlan plan(String userGoal) throws IOException;

    default ExecutionPlan plan(String userGoal, String supplementalContext) throws IOException {
        return plan(userGoal);
    }

    /**
     * 重新规划。
     *
     * <p>当执行失败、计划校验失败或成功率过低时，可以把失败上下文反馈给 LLM 生成新计划。</p>
     */
    default ExecutionPlan replan(ExecutionPlan failedPlan, String failureReason) throws IOException {
        return plan("原目标: " + failedPlan.goal() + "\n失败原因: " + failureReason + "\n请重新制定可执行计划。");
    }
}
