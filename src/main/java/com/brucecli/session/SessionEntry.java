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
    String targetId,
    String customType,
    Object data,
    String content,
    Boolean display,
    Object details,
    String name
) {
    static SessionEntry message(String id, String parentId, String timestamp, Message message) {
        return new SessionEntry("message", id, parentId, timestamp, message, null, null, null, null, null, null, null, null);
    }

    static SessionEntry modeChange(String id, String parentId, String timestamp, AgentMode mode) {
        return new SessionEntry("mode_change", id, parentId, timestamp, null, mode, null, null, null, null, null, null, null);
    }

    static SessionEntry leafChange(String id, String parentId, String timestamp, String targetId) {
        return new SessionEntry("leaf_change", id, parentId, timestamp, null, null, targetId, null, null, null, null, null, null);
    }

    static SessionEntry custom(String id, String parentId, String timestamp, String customType, Object data) {
        return new SessionEntry("custom", id, parentId, timestamp, null, null, null, customType, data, null, null, null, null);
    }

    static SessionEntry customMessage(
        String id,
        String parentId,
        String timestamp,
        String customType,
        String content,
        boolean display,
        Object details
    ) {
        return new SessionEntry(
            "custom_message",
            id,
            parentId,
            timestamp,
            null,
            null,
            null,
            customType,
            null,
            content,
            display,
            details,
            null
        );
    }

    static SessionEntry sessionInfo(String id, String parentId, String timestamp, String name) {
        return new SessionEntry("session_info", id, parentId, timestamp, null, null, null, null, null, null, null, null, name);
    }

    boolean isBranchNode() {
        return "message".equals(type)
            || "mode_change".equals(type)
            || "custom".equals(type)
            || "custom_message".equals(type)
            || "session_info".equals(type);
    }
}
