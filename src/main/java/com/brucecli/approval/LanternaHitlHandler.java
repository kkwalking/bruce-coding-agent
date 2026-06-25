package com.brucecli.approval;

import com.brucecli.render.LanternaBruceRenderer;

import java.util.HashSet;
import java.util.Set;

public class LanternaHitlHandler implements HitlHandler {
    private final LanternaBruceRenderer renderer;
    private final Set<String> approvedAllTools = new HashSet<>();
    private boolean enabled;

    public LanternaHitlHandler(boolean enabled, LanternaBruceRenderer renderer) {
        this.enabled = enabled;
        this.renderer = renderer;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public synchronized ApprovalResult requestApproval(ApprovalRequest request) {
        if (!enabled) {
            return ApprovalResult.approve();
        }
        if (approvedAllTools.contains(request.toolName())) {
            return ApprovalResult.approveAll();
        }
        ApprovalResult result = renderer.requestApproval(request);
        if (result.decision() == ApprovalResult.Decision.APPROVED_ALL) {
            approvedAllTools.add(request.toolName());
        }
        return result;
    }

    @Override
    public synchronized void clearApprovedAll() {
        approvedAllTools.clear();
    }
}
