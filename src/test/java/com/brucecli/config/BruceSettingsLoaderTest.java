package com.brucecli.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BruceSettingsLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void missingSettingsFileLoadsEmptySettings() throws Exception {
        BruceSettings settings = new BruceSettingsLoader(tempDir.resolve("setting.json")).load();

        assertTrue(settings.getLlm().getProviders().isEmpty());
        assertEquals("zhipu", settings.getWebSearch().getProvider());
        assertEquals("search_std", settings.getWebSearch().getZhipu().getSearchEngine());
        assertEquals("ollama", settings.getEmbedding().getProvider());
        assertTrue(settings.getMcp().getServers().isEmpty());
    }

    @Test
    void readsLlmProvidersFromJson() throws Exception {
        Path file = tempDir.resolve("setting.json");
        Files.writeString(file, """
            {
              "llm": {
                "defaultProvider": "glm",
                "defaultModel": "glm-5.1",
                "providers": {
                  "glm": {
                    "apiKey": "glm-key"
                  },
                  "openai_compatiable": {
                    "apiKey": "local-key",
                    "baseUrl": "http://localhost:9000/v1",
                    "models": ["local-model"]
	                  }
	                }
	              },
	              "webSearch": {
	                "provider": "searxng",
	                "zhipu": {
	                  "apiKey": "zhipu-key",
	                  "searchEngine": "search_pro",
	                  "contentSize": "high",
	                  "endpoint": "https://example.com/search"
	                },
	                "serpapi": {"apiKey": "serp-key"},
	                "searxng": {"url": "http://localhost:8888"}
	              },
	              "embedding": {
	                "provider": "glm",
	                "model": "embedding-3",
	                "baseUrl": "https://example.com/v4",
	                "apiKey": "embedding-key"
	              },
	              "storage": {
	                "legacyDir": "~/custom-cache",
	                "ragDir": "/tmp/bruce-rag"
	              },
	              "variables": {
	                "demoToken": "token-value"
	              },
	              "mcp": {
	                "servers": {
	                  "filesystem": {
	                    "command": "npx",
	                    "args": ["-y", "server"],
	                    "env": {"NODE_OPTIONS": "--max-old-space-size=256"}
	                  }
	                }
	              }
	            }
	            """);

        BruceSettingsLoader loader = new BruceSettingsLoader(file);
        BruceSettings settings = loader.load();

        assertEquals("glm", settings.getLlm().getDefaultProvider());
        assertEquals("glm-5.1", settings.getLlm().getDefaultModel());
        assertEquals("glm-key", settings.getLlm().getProviders().get("glm").getApiKey());
        assertEquals(List.of("local-model"), settings.getLlm().getProviders().get("openai_compatiable").getModels());
        assertEquals("searxng", settings.getWebSearch().getProvider());
        assertEquals("zhipu-key", settings.getWebSearch().getZhipu().getApiKey());
        assertEquals("search_pro", settings.getWebSearch().getZhipu().getSearchEngine());
        assertEquals("high", settings.getWebSearch().getZhipu().getContentSize());
        assertEquals("https://example.com/search", settings.getWebSearch().getZhipu().getEndpoint());
        assertEquals("serp-key", settings.getWebSearch().getSerpapi().getApiKey());
        assertEquals("http://localhost:8888", settings.getWebSearch().getSearxng().getUrl());
        assertEquals("glm", settings.getEmbedding().getProvider());
        assertEquals("embedding-3", settings.getEmbedding().getModel());
        assertEquals("https://example.com/v4", settings.getEmbedding().getBaseUrl());
        assertEquals("embedding-key", settings.getEmbedding().getApiKey());
        assertEquals("token-value", settings.getVariables().get("demoToken"));
        assertEquals("npx", settings.getMcp().getServers().get("filesystem").getCommand());
        assertEquals(List.of("-y", "server"), settings.getMcp().getServers().get("filesystem").getArgs());

        loader.save(settings);
        String saved = Files.readString(file);
        assertFalse(saved.contains("\"storage\""));
        assertFalse(saved.contains("legacyDir"));
        assertFalse(saved.contains("ragDir"));
    }

    @Test
    void savesSettingsAsJson() throws Exception {
        Path file = tempDir.resolve("nested/setting.json");
        BruceSettings settings = new BruceSettings();
        settings.getLlm().setDefaultProvider("deepseek");
        settings.getLlm().setDefaultModel("deepseek-v4-pro");

        new BruceSettingsLoader(file).save(settings);

        String saved = Files.readString(file);
        assertTrue(saved.contains("\"defaultProvider\" : \"deepseek\""));
        assertTrue(saved.contains("\"defaultModel\" : \"deepseek-v4-pro\""));
        assertFalse(saved.contains("\"storage\""));
    }

    @Test
    void resolvesHomeRelativePaths() {
        String originalHome = System.getProperty("user.home");
        Path home = tempDir.resolve("home");
        try {
            System.setProperty("user.home", home.toString());

            assertEquals(home.resolve(".bruce/cache").toAbsolutePath().normalize(),
                BruceSettingsLoader.resolveUserPath("~/.bruce/cache"));
            assertEquals(home.toAbsolutePath().normalize(), BruceSettingsLoader.resolveUserPath("~"));
            assertEquals(tempDir.resolve("plain").toAbsolutePath().normalize(),
                BruceSettingsLoader.resolveUserPath(tempDir.resolve("plain").toString()));
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }
}
