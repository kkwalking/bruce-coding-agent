package com.brucecli.session;

import com.brucecli.integrated.runtime.AgentMode;
import com.brucecli.llm.Message;

public record SessionEntry(
    String type,
    String id,
    String parentId,
    String timestamp,
    Message message,
    AgentMode mode,
    String targetId
) {
    static SessionEntry message(String id, String parentId, String timestamp, Message message) {
        return new SessionEntry("message", id, parentId, timestamp, message, null, null);
    }

    static SessionEntry modeChange(String id, String parentId, String timestamp, AgentMode mode) {
        return new SessionEntry("mode_change", id, parentId, timestamp, null, mode, null);
    }

    static SessionEntry leafChange(String id, String parentId, String timestamp, String targetId) {
        return new SessionEntry("leaf_change", id, parentId, timestamp, null, null, targetId);
    }

    boolean isBranchNode() {
        return "message".equals(type) || "mode_change".equals(type);
    }
}
