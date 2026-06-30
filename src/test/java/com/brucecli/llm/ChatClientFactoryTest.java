package com.brucecli.llm;

import com.brucecli.config.BruceSettings;
import com.brucecli.config.BruceSettingsLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatClientFactoryTest {
    @TempDir
    Path tempDir;

    @Test
    void createsSwitchableClientFromJsonSettings() {
        BruceSettings settings = settings();
        settings.getLlm().setDefaultProvider("glm");
        settings.getLlm().setDefaultModel("glm-5.2");
        settings.getLlm().getProviders().put("deepseek", provider("deepseek-key"));
        settings.getLlm().getProviders().put("glm", provider("glm-key"));

        SwitchableChatClient client = ChatClientFactory.create(
            settings,
            new BruceSettingsLoader(tempDir.resolve("setting.json"))
        );

        assertEquals("glm", client.getProviderName());
        assertEquals("glm-5.2", client.getModelName());
        assertTrue(client.modelOptions().contains(new ModelOption("deepseek", "deepseek-v4-pro")));
        assertTrue(client.modelOptions().contains(new ModelOption("glm", "glm-5.1")));
    }

    @Test
    void switchableClientPersistsSelectedDefaultModel() throws Exception {
        Path file = tempDir.resolve("setting.json");
        BruceSettings settings = settings();
        settings.getLlm().setDefaultProvider("deepseek");
        settings.getLlm().setDefaultModel("deepseek-v4-flash");
        settings.getLlm().getProviders().put("deepseek", provider("deepseek-key"));
        SwitchableChatClient client = ChatClientFactory.create(settings, new BruceSettingsLoader(file));

        ModelOption selected = client.switchModel("deepseek/deepseek-v4-pro");

        assertEquals(new ModelOption("deepseek", "deepseek-v4-pro"), selected);
        String saved = Files.readString(file);
        assertTrue(saved.contains("\"defaultProvider\" : \"deepseek\""));
        assertTrue(saved.contains("\"defaultModel\" : \"deepseek-v4-pro\""));
    }

    @Test
    void invalidDefaultModelFallsBackToProviderDefault() {
        BruceSettings settings = settings();
        settings.getLlm().setDefaultProvider("glm");
        settings.getLlm().setDefaultModel("missing-model");
        settings.getLlm().getProviders().put("glm", provider("glm-key"));

        SwitchableChatClient client = ChatClientFactory.create(
            settings,
            new BruceSettingsLoader(tempDir.resolve("setting.json"))
        );

        assertEquals("glm", client.getProviderName());
        assertEquals(GlmClient.DEFAULT_MODEL, client.getModelName());
    }

    @Test
    void createsOpenAiCompatibleOptionsFromJsonModels() {
        BruceSettings settings = settings();
        settings.getLlm().setDefaultProvider("openai_compatiable");
        settings.getLlm().setDefaultModel("local-model");
        BruceSettings.ProviderSettings provider = provider("local-key");
        provider.setBaseUrl("http://localhost:9000/v1");
        provider.setModels(List.of("local-model"));
        settings.getLlm().getProviders().put("openai_compatiable", provider);

        SwitchableChatClient client = ChatClientFactory.create(
            settings,
            new BruceSettingsLoader(tempDir.resolve("setting.json"))
        );

        assertEquals("openai_compatiable", client.getProviderName());
        assertEquals("local-model", client.getModelName());
        assertEquals(List.of(new ModelOption("openai_compatiable", "local-model")), client.modelOptions());
    }

    @Test
    void normalizesGlmProviderAliasesFromSettings() {
        BruceSettings settings = settings();
        settings.getLlm().setDefaultProvider("zhipu");
        settings.getLlm().getProviders().put("zhipu", provider("glm-key"));

        SwitchableChatClient client = ChatClientFactory.create(
            settings,
            new BruceSettingsLoader(tempDir.resolve("setting.json"))
        );

        assertEquals("glm", client.getProviderName());
    }

    private static BruceSettings settings() {
        return new BruceSettings();
    }

    private static BruceSettings.ProviderSettings provider(String apiKey) {
        BruceSettings.ProviderSettings provider = new BruceSettings.ProviderSettings();
        provider.setApiKey(apiKey);
        return provider;
    }
}
