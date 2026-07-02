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
    String name,
    String summary,
    String firstKeptEntryId,
    Integer tokensBefore
) {
    public static final String TYPE_MESSAGE = "message";
    public static final String TYPE_MODE_CHANGE = "mode_change";
    public static final String TYPE_LEAF_CHANGE = "leaf_change";
    public static final String TYPE_CUSTOM = "custom";
    public static final String TYPE_CUSTOM_MESSAGE = "custom_message";
    public static final String TYPE_SESSION_INFO = "session_info";
    public static final String TYPE_COMPACTION = "compaction";

    static SessionEntry message(String id, String parentId, String timestamp, Message message) {
        return new SessionEntry(TYPE_MESSAGE, id, parentId, timestamp, message, null, null, null, null, null, null, null, null, null, null, null);
    }

    static SessionEntry modeChange(String id, String parentId, String timestamp, AgentMode mode) {
        return new SessionEntry(TYPE_MODE_CHANGE, id, parentId, timestamp, null, mode, null, null, null, null, null, null, null, null, null, null);
    }

    static SessionEntry leafChange(String id, String parentId, String timestamp, String targetId) {
        return new SessionEntry(TYPE_LEAF_CHANGE, id, parentId, timestamp, null, null, targetId, null, null, null, null, null, null, null, null, null);
    }

    static SessionEntry custom(String id, String parentId, String timestamp, String customType, Object data) {
        return new SessionEntry(TYPE_CUSTOM, id, parentId, timestamp, null, null, null, customType, data, null, null, null, null, null, null, null);
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
            TYPE_CUSTOM_MESSAGE,
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
            null,
            null,
            null,
            null
        );
    }

    static SessionEntry sessionInfo(String id, String parentId, String timestamp, String name) {
        return new SessionEntry(TYPE_SESSION_INFO, id, parentId, timestamp, null, null, null, null, null, null, null, null, name, null, null, null);
    }

    static SessionEntry compaction(
        String id,
        String parentId,
        String timestamp,
        String summary,
        String firstKeptEntryId,
        int tokensBefore,
        Object details
    ) {
        return new SessionEntry(
            TYPE_COMPACTION,
            id,
            parentId,
            timestamp,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            details,
            null,
            summary == null ? "" : summary,
            firstKeptEntryId,
            Math.max(0, tokensBefore)
        );
    }

    boolean isBranchNode() {
        return TYPE_MESSAGE.equals(type)
            || TYPE_MODE_CHANGE.equals(type)
            || TYPE_CUSTOM.equals(type)
            || TYPE_CUSTOM_MESSAGE.equals(type)
            || TYPE_SESSION_INFO.equals(type)
            || TYPE_COMPACTION.equals(type);
    }
}
