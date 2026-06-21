package com.brucecli.rag.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Embedding 客户端。
 *
 * <p>默认使用 Ollama 本地模型，也兼容 OpenAI 风格的 /embeddings API。</p>
 */
public class EmbeddingClient {
    public static final int MAX_INPUT_CHARS = 2_000;
    private static final String DEFAULT_PROVIDER = "ollama";
    private static final String DEFAULT_OLLAMA_MODEL = "nomic-embed-text:latest";
    private static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";
    private static final String DEFAULT_OPENAI_MODEL = "embedding-3";
    private static final MediaType JSON = MediaType.parse("application/json");

    private final String provider;
    private final String model;
    private final String baseUrl;
    private final String apiKey;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public EmbeddingClient(String provider, String model, String baseUrl, String apiKey) {
        this(provider, model, baseUrl, apiKey, new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(30))
            .readTimeout(120, TimeUnit.SECONDS)
            .build());
    }

    EmbeddingClient(String provider, String model, String baseUrl, String apiKey, OkHttpClient httpClient) {
        this.provider = provider == null || provider.isBlank() ? DEFAULT_PROVIDER : provider;
        this.model = chooseModel(this.provider, model);
        this.baseUrl = chooseBaseUrl(this.provider, baseUrl);
        this.apiKey = apiKey;
        this.httpClient = httpClient;
    }

    public float[] embed(String text) throws IOException {
        String input = text == null ? "" : text;
        if (input.length() > MAX_INPUT_CHARS) {
            input = input.substring(0, MAX_INPUT_CHARS);
        }

        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "ollama" -> embedOllama(input);
            case "openai", "zhipu", "glm" -> embedOpenAICompatible(input);
            default -> embedOllama(input);
        };
    }

    private float[] embedOllama(String input) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("input", input);

        Request request = new Request.Builder()
            .url(trimSlash(baseUrl) + "/api/embed")
            .post(RequestBody.create(body.toString(), JSON))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("Ollama embedding failed: HTTP " + response.code() + "\n" + responseBody);
            }
            JsonNode embeddings = mapper.readTree(responseBody).path("embeddings");
            if (!embeddings.isArray() || embeddings.isEmpty()) {
                throw new IOException("Ollama embedding response missing embeddings[0]");
            }
            return jsonArrayToFloatArray(embeddings.get(0));
        }
    }

    private float[] embedOpenAICompatible(String input) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("input", input);

        Request.Builder requestBuilder = new Request.Builder()
            .url(trimSlash(baseUrl) + "/embeddings")
            .post(RequestBody.create(body.toString(), JSON));
        if (apiKey != null && !apiKey.isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + apiKey);
        }

        try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("OpenAI-compatible embedding failed: HTTP " + response.code() + "\n" + responseBody);
            }
            JsonNode embedding = mapper.readTree(responseBody).path("data").path(0).path("embedding");
            return jsonArrayToFloatArray(embedding);
        }
    }

    private float[] jsonArrayToFloatArray(JsonNode node) throws IOException {
        if (!node.isArray()) {
            throw new IOException("Embedding 响应缺少数组字段");
        }
        float[] vector = new float[node.size()];
        for (int i = 0; i < node.size(); i++) {
            vector[i] = (float) node.get(i).asDouble();
        }
        return vector;
    }

    private static String chooseModel(String provider, String model) {
        if (model != null && !model.isBlank()) {
            return model;
        }
        if ("ollama".equalsIgnoreCase(provider) || provider == null || provider.isBlank()) {
            return DEFAULT_OLLAMA_MODEL;
        }
        return DEFAULT_OPENAI_MODEL;
    }

    private static String chooseBaseUrl(String provider, String baseUrl) {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl;
        }
        if ("ollama".equalsIgnoreCase(provider) || provider == null || provider.isBlank()) {
            return DEFAULT_OLLAMA_BASE_URL;
        }
        return "https://open.bigmodel.cn/api/paas/v4";
    }

    private static String trimSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

}
