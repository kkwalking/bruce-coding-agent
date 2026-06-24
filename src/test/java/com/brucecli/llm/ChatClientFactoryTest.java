package com.brucecli.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChatClientFactoryTest {
    @Test
    void defaultsToDeepSeekProvider() {
        ChatClient client = ChatClientFactory.create(env(Map.of(
            "DEEPSEEK_API_KEY", "deepseek-key"
        )));

        assertInstanceOf(DeepSeekClient.class, client);
        assertEquals("deepseek", client.getProviderName());
        assertEquals(DeepSeekClient.DEFAULT_MODEL, client.getModelName());
    }

    @Test
    void infersGlmProviderFromModelAndUsesGlmKey() {
        ChatClient client = ChatClientFactory.create(env(Map.of(
            "LLM_MODEL", "glm-5v",
            "GLM_API_KEY", "glm-key"
        )));

        assertInstanceOf(GlmClient.class, client);
        assertEquals("glm", client.getProviderName());
        assertEquals("glm-5v", client.getModelName());
        assertEquals(GlmClient.MULTIMODAL_API_URL, ((GlmClient) client).apiUrl);
    }

    @Test
    void explicitProviderControlsProviderSpecificModelAndEndpoint() {
        ChatClient client = ChatClientFactory.create(env(Map.of(
            "LLM_PROVIDER", "deepseek",
            "GLM_MODEL", "glm-5v",
            "DEEPSEEK_MODEL", "deepseek-chat",
            "DEEPSEEK_API_KEY", "deepseek-key"
        )));

        assertInstanceOf(DeepSeekClient.class, client);
        assertEquals("deepseek-chat", client.getModelName());
        assertEquals(DeepSeekClient.DEFAULT_API_URL, ((DeepSeekClient) client).apiUrl);
    }

    @Test
    void genericApiUrlOverridesProviderDefault() {
        ChatClient client = ChatClientFactory.create(env(Map.of(
            "LLM_PROVIDER", "glm",
            "GLM_API_KEY", "glm-key",
            "LLM_API_URL", "http://localhost:9000/v1"
        )));

        assertInstanceOf(GlmClient.class, client);
        assertEquals("http://localhost:9000/v1/chat/completions", ((GlmClient) client).apiUrl);
    }

    @Test
    void throwsWhenProviderApiKeyIsMissing() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ChatClientFactory.create(env(Map.of("LLM_PROVIDER", "glm")))
        );
    }

    private static Function<String, String> env(Map<String, String> values) {
        return values::get;
    }
}
