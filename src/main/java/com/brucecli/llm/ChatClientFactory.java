package com.brucecli.llm;

import java.util.Locale;
import java.util.function.Function;

/**
 * 根据统一 LLM 环境变量创建 ChatClient。
 */
public final class ChatClientFactory {
    private ChatClientFactory() {
    }

    public static ChatClient create() {
        return create(System::getenv);
    }

    public static ChatClient create(Function<String, String> env) {
        Function<String, String> source = env == null ? System::getenv : env;
        String configuredProvider = firstNonBlank(source.apply("LLM_PROVIDER"));
        String globalModel = firstNonBlank(source.apply("LLM_MODEL"));
        String inferredModel = firstNonBlank(
            globalModel,
            source.apply("GLM_MODEL"),
            source.apply("DEEPSEEK_MODEL")
        );
        String provider = normalizeProvider(firstNonBlank(configuredProvider, inferProvider(inferredModel)));

        return switch (provider) {
            case "glm" -> new GlmClient(
                requiredApiKey(provider, firstNonBlank(source.apply("LLM_API_KEY"), source.apply("GLM_API_KEY"))),
                firstNonBlank(globalModel, source.apply("GLM_MODEL"), GlmClient.DEFAULT_MODEL),
                firstNonBlank(
                    source.apply("LLM_API_URL"),
                    source.apply("GLM_API_URL"),
                    source.apply("LLM_BASE_URL"),
                    source.apply("GLM_BASE_URL")
                )
            );
            case "deepseek" -> new DeepSeekClient(
                requiredApiKey(provider, firstNonBlank(source.apply("LLM_API_KEY"), source.apply("DEEPSEEK_API_KEY"))),
                firstNonBlank(globalModel, source.apply("DEEPSEEK_MODEL"), DeepSeekClient.DEFAULT_MODEL),
                firstNonBlank(
                    source.apply("LLM_API_URL"),
                    source.apply("DEEPSEEK_API_URL"),
                    source.apply("LLM_BASE_URL"),
                    source.apply("DEEPSEEK_BASE_URL")
                )
            );
            default -> throw new IllegalArgumentException(
                "不支持的 LLM_PROVIDER: " + provider + "，当前支持 deepseek、glm"
            );
        };
    }

    static String inferProvider(String model) {
        String normalized = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("glm-")) {
            return "glm";
        }
        if (normalized.startsWith("deepseek-")) {
            return "deepseek";
        }
        return "deepseek";
    }

    static String normalizeProvider(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "deepseek";
        }
        return switch (normalized) {
            case "zhipu", "bigmodel", "zhipuai" -> "glm";
            case "deepseek", "glm" -> normalized;
            default -> normalized;
        };
    }

    private static String requiredApiKey(String provider, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                "错误: 未找到 LLM_API_KEY 或 " + providerSpecificKey(provider)
                    + "，请在 .env 或环境变量中配置。"
            );
        }
        return value;
    }

    private static String providerSpecificKey(String provider) {
        return "glm".equals(provider) ? "GLM_API_KEY" : "DEEPSEEK_API_KEY";
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
