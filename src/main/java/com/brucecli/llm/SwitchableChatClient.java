package com.brucecli.llm;

import com.brucecli.config.BruceSettings;
import com.brucecli.config.BruceSettingsLoader;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SwitchableChatClient implements ChatClient, ModelSelectionService {
    private final BruceSettings settings;
    private final BruceSettingsLoader settingsLoader;
    private final List<ModelOption> modelOptions;
    private final Map<String, ClientSupplier> clientSuppliers;
    private final Map<String, String> defaultModels;
    private volatile ChatClient currentClient;
    private volatile ModelOption currentModel;

    public SwitchableChatClient(
        BruceSettings settings,
        BruceSettingsLoader settingsLoader,
        List<ModelOption> modelOptions,
        Map<String, ClientSupplier> clientSuppliers,
        Map<String, String> defaultModels,
        ModelOption initialModel
    ) {
        if (modelOptions == null || modelOptions.isEmpty()) {
            throw new IllegalArgumentException("错误: 未找到可用 LLM provider，请配置 " + settingsPath(settingsLoader) + "。");
        }
        this.settings = settings == null ? new BruceSettings() : settings;
        this.settingsLoader = settingsLoader;
        this.modelOptions = List.copyOf(modelOptions);
        this.clientSuppliers = new LinkedHashMap<>(clientSuppliers == null ? Map.of() : clientSuppliers);
        this.defaultModels = new LinkedHashMap<>(defaultModels == null ? Map.of() : defaultModels);
        ModelOption selected = initialModel == null ? this.modelOptions.get(0) : initialModel;
        this.currentModel = requireKnown(selected);
        this.currentClient = createClient(this.currentModel);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<ToolDefinition> tools) throws IOException {
        return currentClient.chat(messages, tools);
    }

    @Override
    public ChatResponse chat(
        List<Message> messages,
        List<ToolDefinition> tools,
        StreamListener listener
    ) throws IOException {
        return currentClient.chat(messages, tools, listener);
    }

    @Override
    public String getProviderName() {
        return currentModel.provider();
    }

    @Override
    public String getModelName() {
        return currentModel.model();
    }

    @Override
    public int maxContextWindow() {
        return currentClient.maxContextWindow();
    }

    @Override
    public boolean supportsTools() {
        return currentClient.supportsTools();
    }

    @Override
    public boolean supportsPromptCaching() {
        return currentClient.supportsPromptCaching();
    }

    @Override
    public List<ModelOption> modelOptions() {
        return modelOptions;
    }

    @Override
    public ModelOption currentModel() {
        return currentModel;
    }

    @Override
    public synchronized ModelOption switchModel(String selector) {
        ModelOption next = resolveSelector(selector);
        ChatClient nextClient = createClient(next);
        persistDefault(next);
        currentModel = next;
        currentClient = nextClient;
        return next;
    }

    @Override
    public String settingsPath() {
        return settingsPath(settingsLoader);
    }

    private ModelOption resolveSelector(String selector) {
        String value = selector == null ? "" : selector.trim();
        if (value.isBlank()) {
            return currentModel;
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            String provider = ChatClientFactory.normalizeProvider(value.substring(0, slash));
            if (defaultModels.containsKey(provider)) {
                String model = value.substring(slash + 1).trim();
                return find(provider, model);
            }
        }

        String provider = ChatClientFactory.normalizeProvider(value);
        if (defaultModels.containsKey(provider)) {
            return find(provider, defaultModels.get(provider));
        }

        List<ModelOption> matches = modelOptions.stream()
            .filter(option -> option.model().equalsIgnoreCase(value))
            .toList();
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("模型名重复: " + value + "，请使用 /model provider/model。");
        }
        throw new IllegalArgumentException("未知模型: " + value + "，请执行 /model 查看可用模型。");
    }

    private ModelOption find(String provider, String model) {
        if (provider == null || provider.isBlank() || model == null || model.isBlank()) {
            throw new IllegalArgumentException("模型选择格式应为 provider/model。");
        }
        return modelOptions.stream()
            .filter(option -> option.provider().equalsIgnoreCase(provider)
                && option.model().equalsIgnoreCase(model))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "未知模型: " + model + " [" + provider + "]，请执行 /model 查看可用模型。"
            ));
    }

    private ModelOption requireKnown(ModelOption option) {
        return find(option.provider(), option.model());
    }

    private ChatClient createClient(ModelOption option) {
        ClientSupplier supplier = clientSuppliers.get(key(option));
        if (supplier == null) {
            throw new IllegalArgumentException("未知模型: " + option.display());
        }
        return supplier.create();
    }

    private void persistDefault(ModelOption option) {
        if (settingsLoader == null) {
            return;
        }
        BruceSettings.LlmSettings llm = settings.getLlm();
        String oldProvider = llm.getDefaultProvider();
        String oldModel = llm.getDefaultModel();
        llm.setDefaultProvider(option.provider().toLowerCase(Locale.ROOT));
        llm.setDefaultModel(option.model());
        try {
            settingsLoader.save(settings);
        } catch (IOException exception) {
            llm.setDefaultProvider(oldProvider);
            llm.setDefaultModel(oldModel);
            throw new IllegalStateException("保存模型配置失败: " + exception.getMessage(), exception);
        }
    }

    static String key(ModelOption option) {
        return option.provider().toLowerCase(Locale.ROOT) + "/" + option.model().toLowerCase(Locale.ROOT);
    }

    private static String settingsPath(BruceSettingsLoader loader) {
        return loader == null ? BruceSettingsLoader.defaultSettingsFile().toString() : loader.settingsFile().toString();
    }

    @FunctionalInterface
    public interface ClientSupplier {
        ChatClient create();
    }
}
