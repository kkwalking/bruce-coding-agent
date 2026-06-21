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
 * SerpAPI Provider。
 */
public class SerpApiSearchProvider implements SearchProvider {
    private static final String ENDPOINT = "https://serpapi.com/search.json";

    private final String apiKey;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public SerpApiSearchProvider(String apiKey) {
        this(apiKey, new OkHttpClient());
    }

    SerpApiSearchProvider(String apiKey, OkHttpClient httpClient) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.httpClient = httpClient;
    }

    @Override
    public String name() {
        return "serpapi";
    }

    @Override
    public boolean isReady() {
        return !apiKey.isBlank();
    }

    @Override
    public String unavailableHint() {
        return "SerpAPI 未配置：请设置 SERPAPI_KEY，或改用 GLM_API_KEY/SEARXNG_URL。";
    }

    @Override
    public List<WebSearchResult> search(String query, int topK) throws IOException {
        if (!isReady()) {
            throw new IOException(unavailableHint());
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("搜索关键词不能为空");
        }

        HttpUrl url = HttpUrl.parse(ENDPOINT).newBuilder()
            .addQueryParameter("engine", "google")
            .addQueryParameter("q", query.trim())
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("num", String.valueOf(Math.max(1, Math.min(10, topK))))
            .addQueryParameter("hl", "zh-cn")
            .build();

        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("SerpAPI 请求失败: HTTP " + response.code() + "\n" + responseBody);
            }
            return parseResults(responseBody, topK);
        }
    }

    private List<WebSearchResult> parseResults(String responseBody, int topK) throws IOException {
        JsonNode root = mapper.readTree(responseBody);
        List<WebSearchResult> parsed = new ArrayList<>();
        JsonNode organic = root.path("organic_results");
        if (organic.isArray()) {
            for (JsonNode item : organic) {
                parsed.add(new WebSearchResult(
                    item.path("title").asText(""),
                    item.path("link").asText(""),
                    firstText(item, "snippet", "description"),
                    item.path("source").asText(item.path("displayed_link").asText("")),
                    item.path("date").asText("")
                ));
                if (parsed.size() >= topK) {
                    return parsed;
                }
            }
        }

        JsonNode answerBox = root.path("answer_box");
        if (parsed.isEmpty() && answerBox.isObject()) {
            parsed.add(new WebSearchResult(
                answerBox.path("title").asText("精选摘要"),
                answerBox.path("link").asText(""),
                firstText(answerBox, "answer", "snippet"),
                "answer_box",
                ""
            ));
        }
        return parsed;
    }

    private static String firstText(JsonNode node, String first, String second) {
        String firstValue = node.path(first).asText("");
        return firstValue.isBlank() ? node.path(second).asText("") : firstValue;
    }
}
