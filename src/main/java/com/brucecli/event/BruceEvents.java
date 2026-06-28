package com.brucecli.event;

import com.brucecli.integrated.runtime.AgentMode;
import com.brucecli.llm.Message;
import com.brucecli.llm.ToolCall;
import com.brucecli.rag.model.IndexProgress;
import com.brucecli.session.SessionContext;
import com.brucecli.tool.ToolCallResult;

import java.time.Instant;
import java.util.UUID;

public final class BruceEvents {
    private BruceEvents() {
    }

    public static String newRunId() {
        return "r_" + UUID.randomUUID().toString().replace("-", "");
    }

    public record RunStarted(
        String runId,
        Instant timestamp,
        AgentMode mode,
        String input
    ) implements BruceEvent {
        public RunStarted(String runId, AgentMode mode, String input) {
            this(runId, Instant.now(), mode, input);
        }

        @Override
        public String type() {
            return "run_started";
        }
    }

    public record RunCompleted(
        String runId,
        Instant timestamp,
        String output
    ) implements BruceEvent {
        public RunCompleted(String runId, String output) {
            this(runId, Instant.now(), output);
        }

        @Override
        public String type() {
            return "run_completed";
        }
    }

    public record RunFailed(
        String runId,
        Instant timestamp,
        String message
    ) implements BruceEvent {
        public RunFailed(String runId, String message) {
            this(runId, Instant.now(), message);
        }

        @Override
        public String type() {
            return "run_failed";
        }
    }

    public record MessageStarted(
        String runId,
        Instant timestamp,
        String role
    ) implements BruceEvent {
        public MessageStarted(String runId, String role) {
            this(runId, Instant.now(), role);
        }

        @Override
        public String type() {
            return "message_started";
        }
    }

    public record MessageDelta(
        String runId,
        Instant timestamp,
        String role,
        String channel,
        String delta
    ) implements BruceEvent {
        public MessageDelta(String runId, String role, String channel, String delta) {
            this(runId, Instant.now(), role, channel, delta == null ? "" : delta);
        }

        @Override
        public String type() {
            return "message_delta";
        }
    }

    public record MessageCompleted(
        String runId,
        Instant timestamp,
        Message message,
        boolean durable
    ) implements BruceEvent {
        public MessageCompleted(String runId, Message message, boolean durable) {
            this(runId, Instant.now(), message, durable);
        }

        @Override
        public String type() {
            return "message_completed";
        }
    }

    public record ToolCallStarted(
        String runId,
        Instant timestamp,
        ToolCall toolCall
    ) implements BruceEvent {
        public ToolCallStarted(String runId, ToolCall toolCall) {
            this(runId, Instant.now(), toolCall);
        }

        @Override
        public String type() {
            return "tool_call_started";
        }
    }

    public record ToolCallCompleted(
        String runId,
        Instant timestamp,
        ToolCall toolCall,
        String result,
        ToolCallResult.Status status,
        long durationMillis
    ) implements BruceEvent {
        public ToolCallCompleted(String runId, ToolCallResult result) {
            this(
                runId,
                Instant.now(),
                result.toolCall(),
                result.result(),
                result.status(),
                result.durationMillis()
            );
        }

        @Override
        public String type() {
            return "tool_call_completed";
        }
    }

    public record ModeChanged(
        String runId,
        Instant timestamp,
        AgentMode mode
    ) implements BruceEvent {
        public ModeChanged(AgentMode mode) {
            this(null, Instant.now(), mode);
        }

        @Override
        public String type() {
            return "mode_changed";
        }
    }

    public record SessionChanged(
        String runId,
        Instant timestamp,
        String reason,
        SessionContext context
    ) implements BruceEvent {
        public SessionChanged(String reason, SessionContext context) {
            this(null, Instant.now(), reason, context);
        }

        @Override
        public String type() {
            return "session_changed";
        }
    }

    public record Activity(
        String runId,
        Instant timestamp,
        String message
    ) implements BruceEvent {
        public Activity(String runId, String message) {
            this(runId, Instant.now(), message);
        }

        @Override
        public String type() {
            return "activity";
        }
    }

    public record IndexProgressUpdated(
        String runId,
        Instant timestamp,
        IndexProgress progress
    ) implements BruceEvent {
        public IndexProgressUpdated(IndexProgress progress) {
            this(null, Instant.now(), progress);
        }

        @Override
        public String type() {
            return "index_progress_updated";
        }
    }

    public record CustomEvent(
        String runId,
        Instant timestamp,
        String customType,
        Object data
    ) implements BruceEvent {
        public CustomEvent(String runId, String customType, Object data) {
            this(runId, Instant.now(), customType, data);
        }

        @Override
        public String type() {
            return "custom";
        }
    }
}
