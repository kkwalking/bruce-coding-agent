package com.brucecli.llm;

import java.io.IOException;
import java.util.List;

/**
 * 大模型客户端的统一接口。
 *
 * <p>Agent 只依赖这个接口，不直接依赖某个厂商的 SDK 或 HTTP 实现。
 * 这样做的好处是：学习 ReAct 循环时，可以用测试里的假客户端模拟模型返回，
 * 也可以把真实实现从 DeepSeek 换成其他 OpenAI-compatible 服务。</p>
 */
public interface ChatClient {
    /**
     * 发送一轮 Chat Completion 请求。
     *
     * @param messages 到目前为止的完整对话历史，包括 system/user/assistant/tool 消息
     * @param tools 暴露给模型的工具定义，模型会根据这些 JSON Schema 生成 tool_calls
     * @return 模型回复，可能是最终文本，也可能包含一批工具调用
     */
    ChatResponse chat(List<Message> messages, List<ToolDefinition> tools) throws IOException;

    /**
     * 可选流式接口。测试里的假客户端可以只实现非流式方法，默认实现会降级为一次性回调。
     */
    default ChatResponse chat(
        List<Message> messages,
        List<ToolDefinition> tools,
        StreamListener listener
    ) throws IOException {
        ChatResponse response = chat(messages, tools);
        StreamListener target = listener == null ? StreamListener.NO_OP : listener;
        if (response.reasoningContent() != null && !response.reasoningContent().isBlank()) {
            target.onReasoningDelta(response.reasoningContent());
        }
        if (response.content() != null && !response.content().isBlank()) {
            target.onContentDelta(response.content());
        }
        return response;
    }

    default String getProviderName() {
        return "unknown";
    }

    default String getModelName() {
        return "";
    }

    default int maxContextWindow() {
        return 0;
    }

    default boolean supportsTools() {
        return true;
    }

    default boolean supportsPromptCaching() {
        return false;
    }

    /**
     * 流式输出回调。默认空实现方便调用方只关注自己需要展示的片段。
     */
    interface StreamListener {
        StreamListener NO_OP = new StreamListener() {
        };

        default void onReasoningDelta(String delta) {
        }

        default void onContentDelta(String delta) {
        }
    }
}
