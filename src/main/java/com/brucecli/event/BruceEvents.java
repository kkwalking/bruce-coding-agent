package com.brucecli.event;

import com.brucecli.integrated.runtime.AgentMode;
import com.brucecli.llm.Message;
import com.brucecli.llm.ToolCall;
import com.brucecli.rag.model.IndexProgress;
import com.brucecli.session.SessionContext;
import com.brucecli.tool.ToolCallResult;

import java.time.Instant;
import java.util.UUID;

/**
 * bruce 运行时所有标准事件类型的集中定义。
 *
 * <p>统一放在一个类型目录中，方便开发者查找当前运行时会广播哪些事件，也避免事件模型
 * 分散在 Agent、TUI、session 等多个包里。新增事件时优先放到这里，并保持 {@link BruceEvent#type()}
 * 的字符串稳定。</p>
 */
public final class BruceEvents {
    private BruceEvents() {
    }

    /**
     * 创建一次用户任务使用的 runId。
     */
    public static String newRunId() {
        return "r_" + UUID.randomUUID().toString().replace("-", "");
    }

    /*
     * Run lifecycle events
     */

    /**
     * 一次用户任务开始执行。
     */
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

    /**
     * 一次用户任务成功完成。
     */
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

    /**
     * 一次用户任务执行失败。
     */
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

    /*
     * Message stream events
     */

    /**
     * 一条模型消息开始生成，通常用于 UI 开启流式渲染。
     */
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

    /**
     * 模型流式输出的一段增量文本。
     */
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

    /**
     * 一条完整消息已经写入运行时历史或需要被消费端展示。
     */
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

    /*
     * Tool call events
     */

    /**
     * 模型请求的工具调用即将开始执行。
     */
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

    /**
     * 一个工具调用已经执行结束，包含状态、输出和耗时。
     */
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

    /*
     * Runtime state events
     */

    /**
     * 当前 Agent 模式发生变化。
     */
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

    /**
     * 当前 session 上下文发生变化。
     */
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

    /**
     * 运行时产生的通用活动提示。
     */
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

    /**
     * RAG 索引进度发生变化。
     */
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

    /**
     * 预留给扩展能力的自定义事件。
     */
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
