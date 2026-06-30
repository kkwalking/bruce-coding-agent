package com.brucecli.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class BruceSettings {
    private LlmSettings llm = new LlmSettings();

    public LlmSettings getLlm() {
        if (llm == null) {
            llm = new LlmSettings();
        }
        return llm;
    }

    public void setLlm(LlmSettings llm) {
        this.llm = llm == null ? new LlmSettings() : llm;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LlmSettings {
        private String defaultProvider;
        private String defaultModel;
        private Map<String, ProviderSettings> providers = new LinkedHashMap<>();

        public String getDefaultProvider() {
            return defaultProvider;
        }

        public void setDefaultProvider(String defaultProvider) {
            this.defaultProvider = defaultProvider;
        }

        public String getDefaultModel() {
            return defaultModel;
        }

        public void setDefaultModel(String defaultModel) {
            this.defaultModel = defaultModel;
        }

        public Map<String, ProviderSettings> getProviders() {
            if (providers == null) {
                providers = new LinkedHashMap<>();
            }
            return providers;
        }

        public void setProviders(Map<String, ProviderSettings> providers) {
            this.providers = providers == null ? new LinkedHashMap<>() : providers;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProviderSettings {
        private String apiKey;
        private String baseUrl;
        private List<String> models = new ArrayList<>();

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public List<String> getModels() {
            if (models == null) {
                models = new ArrayList<>();
            }
            return models;
        }

        public void setModels(List<String> models) {
            this.models = models == null ? new ArrayList<>() : models;
        }
    }
}
