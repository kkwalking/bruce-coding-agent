package com.brucecli.web.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * SearXNG Provider，适合用户自己部署的免费元搜索。
 */
public class SearxngSearchProvider implements SearchProvider {
    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public SearxngSearchProvider(String baseUrl) {
        this(baseUrl, new OkHttpClient());
    }

    SearxngSearchProvider(String baseUrl, OkHttpClient httpClient) {
        this.baseUrl = trimSlash(baseUrl);
        this.httpClient = httpClient;
    }

    @Override
    public String name() {
        return "searxng";
    }

    @Override
    public boolean isReady() {
        return !baseUrl.isBlank();
    }

    @Override
    public String unavailableHint() {
        return "SearXNG 未配置：请在 ~/.bruce/setting.json 中设置 webSearch.searxng.url。";
    }

    @Override
    public List<WebSearchResult> search(String query, int topK) throws IOException {
        if (!isReady()) {
            throw new IOException(unavailableHint());
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("搜索关键词不能为空");
        }

        HttpUrl base = HttpUrl.parse(baseUrl);
        if (base == null) {
            throw new IOException("webSearch.searxng.url 不是合法 URL: " + baseUrl);
        }
        HttpUrl url = base.newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q", query.trim())
            .addQueryParameter("format", "json")
            .addQueryParameter("language", "zh-CN")
            .addQueryParameter("safesearch", "1")
            .build();

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("SearXNG 请求失败: HTTP " + response.code() + "\n" + responseBody);
            }
            return parseResults(responseBody, topK);
        }
    }

    private List<WebSearchResult> parseResults(String responseBody, int topK) throws IOException {
        JsonNode results = mapper.readTree(responseBody).path("results");
        List<WebSearchResult> parsed = new ArrayList<>();
        if (!results.isArray()) {
            return parsed;
        }
        for (JsonNode item : results) {
            parsed.add(new WebSearchResult(
                item.path("title").asText(""),
                item.path("url").asText(""),
                item.path("content").asText(""),
                item.path("engine").asText(""),
                item.path("publishedDate").asText("")
            ));
            if (parsed.size() >= topK) {
                break;
            }
        }
        return parsed;
    }

    private static String trimSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
