package com.brucecli.web.search;

/**
 * 联网搜索配置。
 *
 * <p>注意：智谱搜索只读取 GLM_API_KEY，刻意不复用 DEEPSEEK_API_KEY。</p>
 */
public record WebSearchConfig(
    String provider,
    String glmApiKey,
    String glmSearchEngine,
    String glmContentSize,
    String serpApiKey,
    String searxngUrl,
    String zhipuEndpoint
) {
    public static final String DEFAULT_GLM_SEARCH_ENGINE = "search_std";
    public static final String DEFAULT_GLM_CONTENT_SIZE = "medium";
    public static final String DEFAULT_ZHIPU_ENDPOINT = "https://open.bigmodel.cn/api/paas/v4/web_search";

    public WebSearchConfig {
        provider = clean(provider);
        glmApiKey = clean(glmApiKey);
        glmSearchEngine = defaultIfBlank(glmSearchEngine, DEFAULT_GLM_SEARCH_ENGINE);
        glmContentSize = defaultIfBlank(glmContentSize, DEFAULT_GLM_CONTENT_SIZE);
        serpApiKey = clean(serpApiKey);
        searxngUrl = clean(searxngUrl);
        zhipuEndpoint = defaultIfBlank(zhipuEndpoint, DEFAULT_ZHIPU_ENDPOINT);
    }

    public static WebSearchConfig empty() {
        return new WebSearchConfig("", "", "", "", "", "", "");
    }

    public boolean hasGlmApiKey() {
        return !glmApiKey.isBlank();
    }

    public boolean hasSerpApiKey() {
        return !serpApiKey.isBlank();
    }

    public boolean hasSearxngUrl() {
        return !searxngUrl.isBlank();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String defaultIfBlank(String value, String fallback) {
        String cleaned = clean(value);
        return cleaned.isBlank() ? fallback : cleaned;
    }
}
