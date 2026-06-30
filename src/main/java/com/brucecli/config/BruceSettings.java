package com.brucecli.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class BruceSettings {
    private LlmSettings llm = new LlmSettings();
    private WebSearchSettings webSearch = new WebSearchSettings();
    private EmbeddingSettings embedding = new EmbeddingSettings();
    private McpSettings mcp = new McpSettings();
    private Map<String, String> variables = new LinkedHashMap<>();

    public LlmSettings getLlm() {
        if (llm == null) {
            llm = new LlmSettings();
        }
        return llm;
    }

    public void setLlm(LlmSettings llm) {
        this.llm = llm == null ? new LlmSettings() : llm;
    }

    public WebSearchSettings getWebSearch() {
        if (webSearch == null) {
            webSearch = new WebSearchSettings();
        }
        return webSearch;
    }

    public void setWebSearch(WebSearchSettings webSearch) {
        this.webSearch = webSearch == null ? new WebSearchSettings() : webSearch;
    }

    public EmbeddingSettings getEmbedding() {
        if (embedding == null) {
            embedding = new EmbeddingSettings();
        }
        return embedding;
    }

    public void setEmbedding(EmbeddingSettings embedding) {
        this.embedding = embedding == null ? new EmbeddingSettings() : embedding;
    }

    public McpSettings getMcp() {
        if (mcp == null) {
            mcp = new McpSettings();
        }
        return mcp;
    }

    public void setMcp(McpSettings mcp) {
        this.mcp = mcp == null ? new McpSettings() : mcp;
    }

    public Map<String, String> getVariables() {
        if (variables == null) {
            variables = new LinkedHashMap<>();
        }
        return variables;
    }

    public void setVariables(Map<String, String> variables) {
        this.variables = variables == null ? new LinkedHashMap<>() : new LinkedHashMap<>(variables);
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WebSearchSettings {
        private String provider = "zhipu";
        private ZhipuSearchSettings zhipu = new ZhipuSearchSettings();
        private SerpApiSearchSettings serpapi = new SerpApiSearchSettings();
        private SearxngSearchSettings searxng = new SearxngSearchSettings();

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public ZhipuSearchSettings getZhipu() {
            if (zhipu == null) {
                zhipu = new ZhipuSearchSettings();
            }
            return zhipu;
        }

        public void setZhipu(ZhipuSearchSettings zhipu) {
            this.zhipu = zhipu == null ? new ZhipuSearchSettings() : zhipu;
        }

        public SerpApiSearchSettings getSerpapi() {
            if (serpapi == null) {
                serpapi = new SerpApiSearchSettings();
            }
            return serpapi;
        }

        public void setSerpapi(SerpApiSearchSettings serpapi) {
            this.serpapi = serpapi == null ? new SerpApiSearchSettings() : serpapi;
        }

        public SearxngSearchSettings getSearxng() {
            if (searxng == null) {
                searxng = new SearxngSearchSettings();
            }
            return searxng;
        }

        public void setSearxng(SearxngSearchSettings searxng) {
            this.searxng = searxng == null ? new SearxngSearchSettings() : searxng;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ZhipuSearchSettings {
        private String apiKey = "";
        private String searchEngine = "search_std";
        private String contentSize = "medium";
        private String endpoint = "https://open.bigmodel.cn/api/paas/v4/web_search";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getSearchEngine() {
            return searchEngine;
        }

        public void setSearchEngine(String searchEngine) {
            this.searchEngine = searchEngine;
        }

        public String getContentSize() {
            return contentSize;
        }

        public void setContentSize(String contentSize) {
            this.contentSize = contentSize;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SerpApiSearchSettings {
        private String apiKey = "";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SearxngSearchSettings {
        private String url = "";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class EmbeddingSettings {
        private String provider = "ollama";
        private String model = "nomic-embed-text:latest";
        private String baseUrl = "http://localhost:11434";
        private String apiKey = "";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class McpSettings {
        private Map<String, McpServerSettings> servers = new LinkedHashMap<>();

        public Map<String, McpServerSettings> getServers() {
            if (servers == null) {
                servers = new LinkedHashMap<>();
            }
            return servers;
        }

        public void setServers(Map<String, McpServerSettings> servers) {
            this.servers = servers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(servers);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class McpServerSettings {
        private String type;
        private String command;
        private List<String> args = new ArrayList<>();
        private Map<String, String> env = new LinkedHashMap<>();
        private String url;
        private Map<String, String> headers = new LinkedHashMap<>();
        private boolean disabled;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getCommand() {
            return command;
        }

        public void setCommand(String command) {
            this.command = command;
        }

        public List<String> getArgs() {
            if (args == null) {
                args = new ArrayList<>();
            }
            return args;
        }

        public void setArgs(List<String> args) {
            this.args = args == null ? new ArrayList<>() : args;
        }

        public Map<String, String> getEnv() {
            if (env == null) {
                env = new LinkedHashMap<>();
            }
            return env;
        }

        public void setEnv(Map<String, String> env) {
            this.env = env == null ? new LinkedHashMap<>() : new LinkedHashMap<>(env);
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public Map<String, String> getHeaders() {
            if (headers == null) {
                headers = new LinkedHashMap<>();
            }
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
        }

        public boolean isDisabled() {
            return disabled;
        }

        public void setDisabled(boolean disabled) {
            this.disabled = disabled;
        }
    }
}
