package com.brucecli.approval;

/**
 * HITL 交互接口，方便在测试里替换为假实现。
 */
public interface HitlHandler {
    boolean isEnabled();

    void setEnabled(boolean enabled);

    ApprovalResult requestApproval(ApprovalRequest request);

    default void clearApprovedAll() {
    }
}
