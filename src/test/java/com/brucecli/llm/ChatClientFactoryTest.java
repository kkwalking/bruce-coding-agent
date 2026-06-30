package com.brucecli.llm;

import com.brucecli.config.BruceSettings;
import com.brucecli.config.BruceSettingsLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void requiresExplicitProvider() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> ChatClientFactory.create(env(Map.of(
                "DEEPSEEK_API_KEY", "deepseek-key"
            )))
        );

        assertTrue(ex.getMessage().contains("LLM_PROVIDER"));
    }

    @Test
    void createsDeepSeekFromProviderSpecificEnv() {
        ChatClient client = ChatClientFactory.create(env(Map.of(
            "LLM_PROVIDER", "deepseek",
            "GLM_MODEL", "glm-5v",
            "DEEPSEEK_API_KEY", "deepseek-key",
            "DEEPSEEK_MODEL", "deepseek-chat"
        )));

        assertInstanceOf(DeepSeekClient.class, client);
        assertEquals("deepseek", client.getProviderName());
        assertEquals("deepseek-chat", client.getModelName());
        assertEquals(DeepSeekClient.DEFAULT_API_URL, ((DeepSeekClient) client).apiUrl);
    }

    @Test
    void createsGlmFromProviderSpecificEnv() {
        ChatClient client = ChatClientFactory.create(env(Map.of(
            "LLM_PROVIDER", "glm",
            "GLM_API_KEY", "glm-key",
            "GLM_MODEL", "glm-5v"
        )));

        assertInstanceOf(GlmClient.class, client);
        assertEquals("glm", client.getProviderName());
        assertEquals("glm-5v", client.getModelName());
        assertEquals(GlmClient.MULTIMODAL_API_URL, ((GlmClient) client).apiUrl);
    }

    @Test
    void ignoresGenericLlmModelAndEndpointVariables() {
        ChatClient client = ChatClientFactory.create(env(Map.of(
            "LLM_PROVIDER", "deepseek",
            "LLM_MODEL", "glm-5v",
            "LLM_API_URL", "http://localhost:9998/v1",
            "LLM_BASE_URL", "http://localhost:9999/v1",
            "DEEPSEEK_API_KEY", "deepseek-key"
        )));

        assertInstanceOf(DeepSeekClient.class, client);
        assertEquals(DeepSeekClient.DEFAULT_MODEL, client.getModelName());
        assertEquals(DeepSeekClient.DEFAULT_API_URL, ((DeepSeekClient) client).apiUrl);
    }

    @Test
    void genericLlmApiKeyDoesNotSatisfyProviderApiKey() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> ChatClientFactory.create(env(Map.of(
                "LLM_PROVIDER", "glm",
                "LLM_API_KEY", "generic-key",
                "LLM_MODEL", "glm-5v"
            )))
        );

        assertTrue(ex.getMessage().contains("GLM_API_KEY"));
    }

    @Test
    void ignoresProviderEndpointOverrideVariables() {
        ChatClient glmClient = ChatClientFactory.create(env(Map.of(
            "LLM_PROVIDER", "glm",
            "GLM_API_KEY", "glm-key",
            "GLM_MODEL", "glm-5v",
            "GLM_API_URL", "http://localhost:9000/v1",
            "GLM_BASE_URL", "http://localhost:9001/v1"
        )));
        ChatClient deepSeekClient = ChatClientFactory.create(env(Map.of(
            "LLM_PROVIDER", "deepseek",
            "DEEPSEEK_API_KEY", "deepseek-key",
            "DEEPSEEK_API_URL", "http://localhost:9002/v1",
            "DEEPSEEK_BASE_URL", "http://localhost:9003/v1"
        )));

        assertEquals(GlmClient.MULTIMODAL_API_URL, ((GlmClient) glmClient).apiUrl);
        assertEquals(DeepSeekClient.DEFAULT_API_URL, ((DeepSeekClient) deepSeekClient).apiUrl);
    }

    @Test
    void createsOpenAiCompatiableFromGenericLlmEnv() {
        ChatClient client = ChatClientFactory.create(env(Map.of(
            "LLM_PROVIDER", "openai_compatiable",
            "LLM_API_KEY", "generic-key",
            "LLM_MODEL", "gpt-compatible",
            "LLM_BASE_URL", "http://localhost:9004/v1",
            "DEEPSEEK_API_KEY", "deepseek-key",
            "GLM_API_KEY", "glm-key"
        )));

        OpenAiCompatiableClient genericClient = assertInstanceOf(OpenAiCompatiableClient.class, client);
        assertEquals("openai_compatiable", genericClient.getProviderName());
        assertEquals("gpt-compatible", genericClient.getModelName());
        assertEquals("http://localhost:9004/v1/chat/completions", genericClient.apiUrl);
    }

    @Test
    void openAiCompatiableRequiresGenericLlmConfig() {
        IllegalArgumentException ex = assertThrows(
            IllegalArgumentException.class,
            () -> ChatClientFactory.create(env(Map.of(
                "LLM_PROVIDER", "openai_compatiable",
                "LLM_API_KEY", "generic-key",
                "LLM_MODEL", "gpt-compatible"
            )))
        );

        assertTrue(ex.getMessage().contains("LLM_BASE_URL"));
    }

    @Test
    void throwsWhenProviderApiKeyIsMissing() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ChatClientFactory.create(env(Map.of("LLM_PROVIDER", "glm")))
        );
    }

    @Test
    void normalizesGlmProviderAliases() {
        ChatClient client = ChatClientFactory.create(env(Map.of(
            "LLM_PROVIDER", "zhipu",
            "GLM_API_KEY", "glm-key"
        )));

        assertInstanceOf(GlmClient.class, client);
        assertEquals("glm", client.getProviderName());
    }

    private static Function<String, String> env(Map<String, String> values) {
        return values::get;
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
