package com.brucecli.web.search;

import okhttp3.OkHttpClient;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 根据配置自动选择搜索 Provider。
 */
public class SearchProviderFactory {
    private SearchProviderFactory() {
    }

    public static SearchProvider create(WebSearchConfig config) {
        return create(config, defaultHttpClient());
    }

    static SearchProvider create(WebSearchConfig config, OkHttpClient httpClient) {
        WebSearchConfig effective = config == null ? WebSearchConfig.empty() : config;
        String provider = pickProvider(
            effective.provider(),
            effective.zhipuApiKey(),
            effective.serpApiKey(),
            effective.searxngUrl()
        );
        return switch (provider) {
            case "zhipu", "glm", "bigmodel" -> new ZhipuSearchProvider(effective, httpClient);
            case "serpapi" -> new SerpApiSearchProvider(effective.serpApiKey(), httpClient);
            case "searxng", "searx" -> new SearxngSearchProvider(effective.searxngUrl(), httpClient);
            default -> new UnavailableSearchProvider(
                provider,
                "未知 webSearch.provider: " + provider + "，支持 zhipu/glm、serpapi、searxng"
            );
        };
    }

    static String pickProvider(String explicit, String zhipuKey, String serpKey, String searxngUrl) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim().toLowerCase(Locale.ROOT);
        }
        if (zhipuKey != null && !zhipuKey.isBlank()) {
            return "zhipu";
        }
        if (serpKey != null && !serpKey.isBlank()) {
            return "serpapi";
        }
        if (searxngUrl != null && !searxngUrl.isBlank()) {
            return "searxng";
        }
        return "zhipu";
    }

    private static OkHttpClient defaultHttpClient() {
        return new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(90, TimeUnit.SECONDS)
            .build();
    }

    private record UnavailableSearchProvider(String name, String unavailableHint) implements SearchProvider {
        @Override
        public boolean isReady() {
            return false;
        }

        @Override
        public java.util.List<WebSearchResult> search(String query, int topK) {
            throw new IllegalStateException(unavailableHint);
        }
    }
}
