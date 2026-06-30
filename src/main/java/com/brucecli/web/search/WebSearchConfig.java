package com.brucecli.web.search;

import com.brucecli.config.BruceSettings;

/**
 * 联网搜索配置。
 */
public record WebSearchConfig(
    String provider,
    String zhipuApiKey,
    String zhipuSearchEngine,
    String zhipuContentSize,
    String serpApiKey,
    String searxngUrl,
    String zhipuEndpoint
) {
    public static final String DEFAULT_ZHIPU_SEARCH_ENGINE = "search_std";
    public static final String DEFAULT_ZHIPU_CONTENT_SIZE = "medium";
    public static final String DEFAULT_ZHIPU_ENDPOINT = "https://open.bigmodel.cn/api/paas/v4/web_search";

    public WebSearchConfig {
        provider = clean(provider);
        zhipuApiKey = clean(zhipuApiKey);
        zhipuSearchEngine = defaultIfBlank(zhipuSearchEngine, DEFAULT_ZHIPU_SEARCH_ENGINE);
        zhipuContentSize = defaultIfBlank(zhipuContentSize, DEFAULT_ZHIPU_CONTENT_SIZE);
        serpApiKey = clean(serpApiKey);
        searxngUrl = clean(searxngUrl);
        zhipuEndpoint = defaultIfBlank(zhipuEndpoint, DEFAULT_ZHIPU_ENDPOINT);
    }

    public static WebSearchConfig empty() {
        return new WebSearchConfig("", "", "", "", "", "", "");
    }

    public static WebSearchConfig fromSettings(BruceSettings.WebSearchSettings settings) {
        BruceSettings.WebSearchSettings source = settings == null ? new BruceSettings.WebSearchSettings() : settings;
        BruceSettings.ZhipuSearchSettings zhipu = source.getZhipu();
        BruceSettings.SerpApiSearchSettings serpapi = source.getSerpapi();
        BruceSettings.SearxngSearchSettings searxng = source.getSearxng();
        return new WebSearchConfig(
            source.getProvider(),
            zhipu.getApiKey(),
            zhipu.getSearchEngine(),
            zhipu.getContentSize(),
            serpapi.getApiKey(),
            searxng.getUrl(),
            zhipu.getEndpoint()
        );
    }

    public boolean hasZhipuApiKey() {
        return !zhipuApiKey.isBlank();
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
