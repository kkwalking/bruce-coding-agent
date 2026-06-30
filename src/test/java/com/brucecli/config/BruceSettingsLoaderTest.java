package com.brucecli.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BruceSettingsLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void missingSettingsFileLoadsEmptySettings() throws Exception {
        BruceSettings settings = new BruceSettingsLoader(tempDir.resolve("setting.json")).load();

        assertTrue(settings.getLlm().getProviders().isEmpty());
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
              }
            }
            """);

        BruceSettings settings = new BruceSettingsLoader(file).load();

        assertEquals("glm", settings.getLlm().getDefaultProvider());
        assertEquals("glm-5.1", settings.getLlm().getDefaultModel());
        assertEquals("glm-key", settings.getLlm().getProviders().get("glm").getApiKey());
        assertEquals(List.of("local-model"), settings.getLlm().getProviders().get("openai_compatiable").getModels());
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
    }
}
