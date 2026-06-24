package com.brucecli.llm;

import java.util.Locale;
import java.util.function.Function;

/**
 * 根据显式 LLM_PROVIDER 和厂商环境变量创建 ChatClient。
 */
public final class ChatClientFactory {
    private ChatClientFactory() {
    }

    public static ChatClient create() {
        return create(System::getenv);
    }

    public static ChatClient create(Function<String, String> env) {
        Function<String, String> source = env == null ? System::getenv : env;
        String provider = requiredProvider(source.apply("LLM_PROVIDER"));

        return switch (provider) {
            case "glm" -> new GlmClient(
                requiredApiKey(provider, source.apply("GLM_API_KEY")),
                firstNonBlank(source.apply("GLM_MODEL"), GlmClient.DEFAULT_MODEL)
            );
            case "deepseek" -> new DeepSeekClient(
                requiredApiKey(provider, source.apply("DEEPSEEK_API_KEY")),
                firstNonBlank(source.apply("DEEPSEEK_MODEL"), DeepSeekClient.DEFAULT_MODEL)
            );
            case OpenAiCompatiableClient.PROVIDER -> new OpenAiCompatiableClient(
                requiredValue("LLM_API_KEY", source.apply("LLM_API_KEY")),
                requiredValue("LLM_MODEL", source.apply("LLM_MODEL")),
                requiredValue("LLM_BASE_URL", source.apply("LLM_BASE_URL"))
            );
            default -> throw new IllegalArgumentException(
                "不支持的 LLM_PROVIDER: " + provider + "，当前支持 deepseek、glm、openai_compatiable"
            );
        };
    }

    private static String requiredProvider(String value) {
        String provider = firstNonBlank(value);
        if (provider == null) {
            throw new IllegalArgumentException(
                "错误: 未找到 LLM_PROVIDER，请在 .env 或环境变量中显式配置 deepseek、glm 或 openai_compatiable。"
            );
        }
        return normalizeProvider(provider);
    }

    static String normalizeProvider(String provider) {
        String normalized = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "zhipu", "bigmodel", "zhipuai" -> "glm";
            case "deepseek", "glm", OpenAiCompatiableClient.PROVIDER -> normalized;
            default -> normalized;
        };
    }

    private static String requiredApiKey(String provider, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                "错误: 未找到 " + providerSpecificKey(provider) + "，请在 .env 或环境变量中配置。"
            );
        }
        return value;
    }

    private static String requiredValue(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                "错误: 未找到 " + key + "，请在 .env 或环境变量中配置。"
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
