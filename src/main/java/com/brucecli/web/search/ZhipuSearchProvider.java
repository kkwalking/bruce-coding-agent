package com.brucecli.web.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 智谱 Web Search API Provider。
 * https://docs.bigmodel.cn/api-reference/%E5%B7%A5%E5%85%B7-api/%E7%BD%91%E7%BB%9C%E6%90%9C%E7%B4%A2
 */
public class ZhipuSearchProvider implements SearchProvider {
    private static final int MAX_QUERY_CHARS = 70;
    private static final MediaType JSON = MediaType.parse("application/json");

    private final WebSearchConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public ZhipuSearchProvider(WebSearchConfig config) {
        this(config, new OkHttpClient());
    }

    ZhipuSearchProvider(WebSearchConfig config, OkHttpClient httpClient) {
        this.config = config == null ? WebSearchConfig.empty() : config;
        this.httpClient = httpClient;
    }

    @Override
    public String name() {
        return "zhipu";
    }

    @Override
    public boolean isReady() {
        return config.hasZhipuApiKey();
    }

    @Override
    public String unavailableHint() {
        return "智谱搜索未配置：请在 ~/.bruce/setting.json 中设置 webSearch.zhipu.apiKey。";
    }

    @Override
    public List<WebSearchResult> search(String query, int topK) throws IOException {
        if (!isReady()) {
            throw new IOException(unavailableHint());
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.put("search_query", normalizeQuery(query));
        payload.put("search_engine", config.zhipuSearchEngine());
        payload.put("search_intent", false);
        payload.put("count", clamp(topK, 1, 50));
        payload.put("search_recency_filter", "noLimit");
        payload.put("content_size", config.zhipuContentSize());

        Request request = new Request.Builder()
            .url(config.zhipuEndpoint())
            .header("Authorization", "Bearer " + config.zhipuApiKey())
            .header("Content-Type", "application/json")
            .post(RequestBody.create(payload.toString(), JSON))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("智谱搜索请求失败: HTTP " + response.code() + "\n" + responseBody);
            }
            return parseResults(responseBody);
        }
    }

    private List<WebSearchResult> parseResults(String responseBody) throws IOException {
        JsonNode results = mapper.readTree(responseBody).path("search_result");
        List<WebSearchResult> parsed = new ArrayList<>();
        if (!results.isArray()) {
            return parsed;
        }
        for (JsonNode item : results) {
            parsed.add(new WebSearchResult(
                item.path("title").asText(""),
                item.path("link").asText(""),
                item.path("content").asText(""),
                item.path("media").asText(""),
                item.path("publish_date").asText("")
            ));
        }
        return parsed;
    }

    private static String normalizeQuery(String query) {
        String cleaned = query == null ? "" : query.trim();
        if (cleaned.isBlank()) {
            throw new IllegalArgumentException("搜索关键词不能为空");
        }
        return cleaned.length() <= MAX_QUERY_CHARS ? cleaned : cleaned.substring(0, MAX_QUERY_CHARS);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
