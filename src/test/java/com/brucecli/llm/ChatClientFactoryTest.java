package com.brucecli.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatClientFactoryTest {
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
}
