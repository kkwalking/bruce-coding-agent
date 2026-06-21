package com.brucecli.agent.multi.model;

/**
 * Multi-Agent 中的三个固定角色。
 *
 * <p>文章采用主从模式：编排器负责调度，Planner/Worker/Reviewer
 * 各自只专注一件事，避免单 Agent 在规划、执行、验收之间来回摇摆。</p>
 */
public enum AgentRole {
    PLANNER,
    WORKER,
    REVIEWER
}
