package com.brucecli.plan.model;

/**
 * 文章中定义的 5 种任务状态。
 *
 * <p>生命周期：PENDING -> RUNNING -> COMPLETED/FAILED/SKIPPED。</p>
 */
public enum TaskStatus {
    /**
     * 等待执行，依赖还没满足或还没被调度。
     */
    PENDING,

    /**
     * 正在执行。
     */
    RUNNING,

    /**
     * 执行完成。
     */
    COMPLETED,

    /**
     * 执行失败。
     */
    FAILED,

    /**
     * 因依赖失败或计划不可达而跳过。
     */
    SKIPPED
}
