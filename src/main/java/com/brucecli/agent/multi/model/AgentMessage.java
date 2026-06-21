package com.brucecli.agent.multi.model;

/**
 * Agent 间通信消息。
 *
 * <p>子 Agent 之间不直接对话，所有消息都经过编排器路由。这个 record
 * 用来标明消息来源、角色、内容和消息类型。</p>
 */
public record AgentMessage(
    String fromAgent,
    AgentRole fromRole,
    String content,
    Type type
) {
    public AgentMessage {
        content = content == null ? "" : content;
        type = type == null ? Type.RESULT : type;
    }

    public enum Type {
        TASK, RESULT, FEEDBACK, APPROVAL, REJECTION, ERROR
    }

    public static AgentMessage task(String fromAgent, AgentRole fromRole, String content) {
        return new AgentMessage(fromAgent, fromRole, content, Type.TASK);
    }

    public static AgentMessage result(String fromAgent, AgentRole fromRole, String content) {
        return new AgentMessage(fromAgent, fromRole, content, Type.RESULT);
    }

    public static AgentMessage error(String fromAgent, AgentRole fromRole, String content) {
        return new AgentMessage(fromAgent, fromRole, content, Type.ERROR);
    }
}
