package com.brucecli.plan.model;

/**
 * 执行计划的状态。
 *
 * <p>文章截图中 ExecutionPlan 拥有独立的 PlanStatus。
 * 它和 TaskStatus 的区别是：TaskStatus 描述单个任务，PlanStatus 描述整张 DAG 计划。</p>
 */
public enum PlanStatus {
    /**
     * 已创建，还未开始执行。
     */
    CREATED,

    /**
     * 正在执行。
     */
    RUNNING,

    /**
     * 所有关键任务执行完成。
     */
    COMPLETED,

    /**
     * 至少一个任务失败，且计划最终没有全部完成。
     */
    FAILED
}
