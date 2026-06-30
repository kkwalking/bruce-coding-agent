package com.brucecli.llm;

import com.brucecli.config.BruceSettings;
import com.brucecli.config.BruceSettingsLoader;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    public static SwitchableChatClient create(BruceSettings settings, BruceSettingsLoader settingsLoader) {
        BruceSettings source = settings == null ? new BruceSettings() : settings;
        BruceSettings.LlmSettings llm = source.getLlm();
        if (llm.getProviders().isEmpty()) {
            throw new IllegalArgumentException(
                "错误: 未在 " + settingsPath(settingsLoader) + " 中配置 llm.providers。"
            );
        }

        List<ModelOption> options = new ArrayList<>();
        Map<String, SwitchableChatClient.ClientSupplier> suppliers = new LinkedHashMap<>();
        Map<String, String> defaultModels = new LinkedHashMap<>();

        for (Map.Entry<String, BruceSettings.ProviderSettings> entry : llm.getProviders().entrySet()) {
            String provider = normalizeProvider(entry.getKey());
            BruceSettings.ProviderSettings providerSettings = entry.getValue();
            if (providerSettings == null || providerSettings.getApiKey() == null || providerSettings.getApiKey().isBlank()) {
                continue;
            }
            if (OpenAiCompatiableClient.PROVIDER.equals(provider)
                && (providerSettings.getBaseUrl() == null || providerSettings.getBaseUrl().isBlank())) {
                continue;
            }

            List<String> models = supportedModels(provider, providerSettings);
            if (models.isEmpty()) {
                continue;
            }
            defaultModels.putIfAbsent(provider, defaultModelFor(provider, models));
            for (String model : models) {
                if (model == null || model.isBlank()) {
                    continue;
                }
                ModelOption option = new ModelOption(provider, model);
                suppliers.putIfAbsent(SwitchableChatClient.key(option), () -> create(provider, model, providerSettings));
                if (options.stream().noneMatch(existing -> existing.matches(option))) {
                    options.add(option);
                }
            }
        }

        if (options.isEmpty()) {
            throw new IllegalArgumentException(
                "错误: " + settingsPath(settingsLoader)
                    + " 中没有可用 LLM provider，请检查 apiKey、baseUrl 和 models 配置。"
            );
        }

        return new SwitchableChatClient(
            source,
            settingsLoader,
            options,
            suppliers,
            defaultModels,
            initialModel(llm, options, defaultModels)
        );
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
            case "zai", "zhipu", "bigmodel", "zhipuai" -> "glm";
            case "openai-compatible", "openai_compatible", "openai", "compatible", OpenAiCompatiableClient.PROVIDER ->
                OpenAiCompatiableClient.PROVIDER;
            case "deepseek", "glm" -> normalized;
            default -> normalized;
        };
    }

    private static ChatClient create(
        String provider,
        String model,
        BruceSettings.ProviderSettings settings
    ) {
        return switch (provider) {
            case "glm" -> new GlmClient(settings.getApiKey(), model);
            case "deepseek" -> new DeepSeekClient(settings.getApiKey(), model);
            case OpenAiCompatiableClient.PROVIDER -> new OpenAiCompatiableClient(
                settings.getApiKey(),
                model,
                settings.getBaseUrl()
            );
            default -> throw new IllegalArgumentException("不支持的 LLM provider: " + provider);
        };
    }

    private static List<String> supportedModels(String provider, BruceSettings.ProviderSettings settings) {
        return switch (provider) {
            case "glm" -> GlmClient.SUPPORTED_MODELS;
            case "deepseek" -> DeepSeekClient.SUPPORTED_MODELS;
            case OpenAiCompatiableClient.PROVIDER -> settings.getModels();
            default -> List.of();
        };
    }

    private static String defaultModelFor(String provider, List<String> models) {
        return switch (provider) {
            case "glm" -> GlmClient.DEFAULT_MODEL;
            case "deepseek" -> DeepSeekClient.DEFAULT_MODEL;
            default -> models.isEmpty() ? "" : models.get(0);
        };
    }

    private static ModelOption initialModel(
        BruceSettings.LlmSettings llm,
        List<ModelOption> options,
        Map<String, String> defaultModels
    ) {
        String configuredProvider = normalizeProvider(llm.getDefaultProvider());
        String configuredModel = llm.getDefaultModel();
        if (!configuredProvider.isBlank() && configuredModel != null && !configuredModel.isBlank()) {
            for (ModelOption option : options) {
                if (option.provider().equalsIgnoreCase(configuredProvider)
                    && option.model().equalsIgnoreCase(configuredModel)) {
                    return option;
                }
            }
        }
        if (!configuredProvider.isBlank() && defaultModels.containsKey(configuredProvider)) {
            String defaultModel = defaultModels.get(configuredProvider);
            for (ModelOption option : options) {
                if (option.provider().equalsIgnoreCase(configuredProvider)
                    && option.model().equalsIgnoreCase(defaultModel)) {
                    return option;
                }
            }
        }
        return options.get(0);
    }

    private static String settingsPath(BruceSettingsLoader settingsLoader) {
        return settingsLoader == null
            ? BruceSettingsLoader.defaultSettingsFile().toString()
            : settingsLoader.settingsFile().toString();
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
