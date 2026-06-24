package com.brucecli.llm;

import okhttp3.OkHttpClient;

/**
 * 智谱 GLM provider 客户端。
 *
 * <p>GLM 文本/编码模型默认走 coding endpoint；GLM-5V 多模态模型默认走通用多模态 endpoint。
 * 显式配置 LLM_API_URL/GLM_API_URL 时会覆盖默认 endpoint。</p>
 */
public class GlmClient extends OpenAiCompatibleChatClient {
    public static final String CODING_API_URL = "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions";
    public static final String MULTIMODAL_API_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    public static final String DEFAULT_MODEL = "glm-5.1";

    public GlmClient(String apiKey, String model) {
        this(apiKey, model, (String) null);
    }

    public GlmClient(String apiKey, String model, String apiUrl) {
        this(apiKey, model, apiUrl, defaultHttpClient());
    }

    GlmClient(String apiKey, String model, OkHttpClient httpClient) {
        this(apiKey, model, null, httpClient);
    }

    GlmClient(String apiKey, String model, String apiUrl, OkHttpClient httpClient) {
        super(
            apiKey,
            model == null || model.isBlank() ? DEFAULT_MODEL : model,
            selectApiUrl(model == null || model.isBlank() ? DEFAULT_MODEL : model, apiUrl),
            httpClient
        );
    }

    @Override
    public String getProviderName() {
        return "glm";
    }

    @Override
    public int maxContextWindow() {
        return 200_000;
    }

    @Override
    public boolean supportsPromptCaching() {
        return true;
    }

    @Override
    protected String toImageUrl(ContentPart part) {
        String value = super.toImageUrl(part);
        if (!isVisionModel(model)) {
            return value;
        }
        int comma = value.indexOf(',');
        if (value.startsWith("data:") && comma >= 0 && comma + 1 < value.length()) {
            return value.substring(comma + 1);
        }
        return value;
    }

    private static String selectApiUrl(String model, String configuredApiUrl) {
        if (configuredApiUrl != null && !configuredApiUrl.isBlank()) {
            return toChatCompletionsUrl(configuredApiUrl);
        }
        return isVisionModel(model) ? MULTIMODAL_API_URL : CODING_API_URL;
    }

    private static boolean isVisionModel(String model) {
        return model != null && model.trim().toLowerCase().startsWith("glm-5v");
    }
}
