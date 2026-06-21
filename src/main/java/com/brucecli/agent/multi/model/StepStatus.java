package com.brucecli.agent.multi.model;

/**
 * 编排器调度步骤时使用的状态。
 */
public enum StepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED
}
