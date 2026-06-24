package com.brucecli.llm;

import okhttp3.OkHttpClient;

/**
 * 通用 OpenAI-compatible provider 客户端。
 */
public class OpenAiCompatiableClient extends OpenAiCompatibleChatClient {
    public static final String PROVIDER = "openai_compatiable";

    public OpenAiCompatiableClient(String apiKey, String model, String baseUrl) {
        this(apiKey, model, baseUrl, defaultHttpClient());
    }

    OpenAiCompatiableClient(String apiKey, String model, String baseUrl, OkHttpClient httpClient) {
        super(apiKey, model, toChatCompletionsUrl(baseUrl), httpClient);
    }

    @Override
    public String getProviderName() {
        return PROVIDER;
    }
}
