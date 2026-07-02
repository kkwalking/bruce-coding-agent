package com.brucecli.llm;

import java.util.List;

/**
 * Chat Completion API 的 message 结构。
 *
 * <p>四类消息的作用：
 * system: 给模型设定角色和规则；
 * user: 用户输入；
 * assistant: 模型回复，可能附带 tool_calls；
 * tool: 工具执行结果，需要通过 toolCallId 关联回某一次工具调用。</p>
 */
public record Message(
    String role,
    String content,
    String reasoningContent,
    List<ToolCall> toolCalls,
    String toolCallId,
    List<ContentPart> contentParts,
    Integer inputTokens,
    Integer outputTokens,
    Integer cachedInputTokens
) {
    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    public Message(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
        this(role, content, null, toolCalls, toolCallId, null);
    }

    public Message(
        String role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId,
        List<ContentPart> contentParts
    ) {
        this(role, content, null, toolCalls, toolCallId, contentParts, null, null, null);
    }

    public Message(
        String role,
        String content,
        String reasoningContent,
        List<ToolCall> toolCalls,
        String toolCallId,
        List<ContentPart> contentParts
    ) {
        this(role, content, reasoningContent, toolCalls, toolCallId, contentParts, null, null, null);
    }

    public Message {
        // record 默认只是保存引用，这里复制一份，避免外部修改历史消息里的工具调用列表。
        reasoningContent = reasoningContent == null ? "" : reasoningContent;
        toolCalls = toolCalls == null ? null : List.copyOf(toolCalls);
        contentParts = contentParts == null ? null : List.copyOf(contentParts);
        inputTokens = positiveOrNull(inputTokens);
        outputTokens = positiveOrNull(outputTokens);
        cachedInputTokens = positiveOrNull(cachedInputTokens);
    }

    /**
     * system 消息通常只在对话开头出现一次，用来告诉模型“你是谁、能做什么、如何使用工具”。
     */
    public static Message system(String content) {
        return new Message(ROLE_SYSTEM, content, null, null);
    }

    /**
     * 用户自然语言输入。
     */
    public static Message user(String content) {
        return new Message(ROLE_USER, content, null, null);
    }

    /**
     * 用户多模态输入。content 保留一份纯文本 fallback，方便 RAG 和日志继续工作。
     */
    public static Message user(List<ContentPart> contentParts) {
        return new Message(
            ROLE_USER,
            plainText(contentParts),
            null,
            null,
            contentParts == null ? null : List.copyOf(contentParts)
        );
    }

    /**
     * 不带工具调用的模型回复，也就是最终答复。
     */
    public static Message assistant(String content) {
        return new Message(ROLE_ASSISTANT, content, null, null);
    }

    public static Message assistant(String reasoningContent, String content) {
        return new Message(ROLE_ASSISTANT, content, reasoningContent, null, null, null);
    }

    /**
     * 带工具调用的模型回复。
     *
     * <p>注意：这类 assistant 消息必须被放回历史，
     * 否则下一轮模型看不到自己刚才请求了哪些工具。</p>
     */
    public static Message assistant(String content, List<ToolCall> toolCalls) {
        return new Message(ROLE_ASSISTANT, content == null ? "" : content, toolCalls, null);
    }

    public static Message assistant(String reasoningContent, String content, List<ToolCall> toolCalls) {
        return new Message(ROLE_ASSISTANT, content == null ? "" : content, reasoningContent, toolCalls, null, null);
    }

    public static Message assistant(ChatResponse response) {
        if (response == null) {
            return assistant("");
        }
        return new Message(
            ROLE_ASSISTANT,
            response.content(),
            response.reasoningContent(),
            response.toolCalls(),
            null,
            null,
            response.inputTokens(),
            response.outputTokens(),
            response.cachedInputTokens()
        );
    }

    /**
     * 工具执行结果。
     *
     * @param toolCallId 对应 assistant.tool_calls[i].id，模型靠它知道这是哪个工具调用的返回值
     */
    public static Message tool(String toolCallId, String content) {
        return new Message(ROLE_TOOL, content, null, toolCallId);
    }

    public boolean hasContentParts() {
        return contentParts != null && !contentParts.isEmpty();
    }

    public boolean hasImageContent() {
        return imagePartCount() > 0;
    }

    public int imagePartCount() {
        if (!hasContentParts()) {
            return 0;
        }
        int count = 0;
        for (ContentPart part : contentParts) {
            if (part != null && part.isImagePart()) {
                count++;
            }
        }
        return count;
    }

    public Message withoutImageContent() {
        if (!hasImageContent()) {
            return this;
        }
        return new Message(
            role,
            content == null || content.isBlank()
                ? "[历史图片内容已移除，仅保留文字占位]"
                : content + "\n[历史图片内容已移除，仅保留文字占位]",
            reasoningContent,
            toolCalls,
            toolCallId,
            List.of(ContentPart.text("[历史图片内容已移除，仅保留文字占位]")),
            inputTokens,
            outputTokens,
            cachedInputTokens
        );
    }

    public Message withoutReasoningContent() {
        if (reasoningContent == null || reasoningContent.isBlank()) {
            return this;
        }
        return new Message(role, content, "", toolCalls, toolCallId, contentParts, inputTokens, outputTokens, cachedInputTokens);
    }

    public int totalUsageTokens() {
        return nullToZero(inputTokens) + nullToZero(outputTokens);
    }

    private static Integer positiveOrNull(Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static String plainText(List<ContentPart> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (ContentPart part : parts) {
            if (part == null) {
                continue;
            }
            String value = part.fallbackText();
            if (value == null || value.isBlank()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(value);
        }
        return text.toString();
    }
}
