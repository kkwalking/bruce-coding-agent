package com.brucecli.llm;

import java.util.List;

/**
 * 大模型一次回复的统一表示。
 *
 * <p>在 ReAct 模式里，模型回复有两种形态：
 * 一种是普通文本，说明任务已经完成；另一种包含 tool_calls，表示模型还需要调用工具继续观察。</p>
 */
public record ChatResponse(
    String role,
    String content,
    String reasoningContent,
    List<ToolCall> toolCalls,
    int inputTokens,
    int outputTokens,
    int cachedInputTokens
) {
    public ChatResponse(String content, List<ToolCall> toolCalls) {
        this("assistant", content, "", toolCalls, 0, 0, 0);
    }

    public ChatResponse {
        role = role == null || role.isBlank() ? "assistant" : role;
        content = content == null ? "" : content;
        reasoningContent = reasoningContent == null ? "" : reasoningContent;
        // 避免调用方到处判空，让“没有工具调用”统一表现为空列表。
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /**
     * 是否需要执行工具。
     */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public boolean hasReasoningContent() {
        return !reasoningContent.isBlank();
    }
}
