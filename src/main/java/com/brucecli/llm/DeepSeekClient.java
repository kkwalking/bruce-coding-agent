package com.brucecli.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * DeepSeek provider 客户端。
 *
 * <p>OpenAI-compatible 协议细节由 {@link OpenAiCompatibleChatClient} 承接，
 * 这里仅保留 DeepSeek 的默认模型、endpoint 和少量请求差异。</p>
 */
public class DeepSeekClient extends OpenAiCompatibleChatClient {
    public static final String DEFAULT_API_URL = "https://api.deepseek.com/chat/completions";
    public static final String DEFAULT_MODEL = "deepseek-v4-flash";

    public DeepSeekClient(String apiKey, String model) {
        this(apiKey, model, (String) null);
    }

    public DeepSeekClient(String apiKey, String model, String apiUrl) {
        this(apiKey, model, apiUrl, defaultDeepSeekHttpClient());
    }

    DeepSeekClient(String apiKey, String model, OkHttpClient httpClient) {
        this(apiKey, model, null, httpClient);
    }

    DeepSeekClient(String apiKey, String model, String apiUrl, OkHttpClient httpClient) {
        super(
            apiKey,
            model == null || model.isBlank() ? DEFAULT_MODEL : model,
            apiUrl == null || apiUrl.isBlank() ? DEFAULT_API_URL : toChatCompletionsUrl(apiUrl),
            httpClient
        );
    }

    @Override
    public String getProviderName() {
        return "deepseek";
    }

    @Override
    public int maxContextWindow() {
        return 1_000_000;
    }

    @Override
    public boolean supportsPromptCaching() {
        return true;
    }

    @Override
    protected boolean shouldSendReasoningContentInRequestHistory() {
        return true;
    }

    @Override
    protected void customizeRequestBody(
        ObjectNode requestBody,
        List<Message> messages,
        List<ToolDefinition> tools,
        boolean stream
    ) {
        // Tool Call 学习场景需要模型直接给出工具调用或最终回答，所以默认关闭 DeepSeek 思考模式。
        requestBody.putObject("thinking").put("type", "disabled");
    }

    private static OkHttpClient defaultDeepSeekHttpClient() {
        return new OkHttpClient.Builder()
            .protocols(List.of(Protocol.HTTP_1_1))
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();
    }
}
