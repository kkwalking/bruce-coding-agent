package com.brucecli.mcp.transport;

import com.brucecli.mcp.config.McpServerConfig;
import com.brucecli.mcp.protocol.McpProtocol;
import com.brucecli.mcp.runtime.LogRingBuffer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class StreamableHttpMcpTransport implements McpTransport {
    private static final MediaType JSON = MediaType.parse("application/json");

    private final McpServerConfig config;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final LogRingBuffer logs = new LogRingBuffer(200);
    private String sessionId;

    public StreamableHttpMcpTransport(McpServerConfig config) {
        this(config, new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(20))
            .readTimeout(90, TimeUnit.SECONDS)
            .build());
    }

    StreamableHttpMcpTransport(McpServerConfig config, OkHttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
    }

    @Override
    public void start() {
        if (config.url().isBlank()) {
            throw new IllegalArgumentException("HTTP MCP server 缺少 url: " + config.name());
        }
    }

    @Override
    public JsonNode request(JsonNode message, Duration timeout) throws Exception {
        try (Response response = httpClient.newCall(requestBuilder(message).build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            captureSession(response);
            if (!response.isSuccessful()) {
                throw new IllegalStateException("HTTP MCP 请求失败: HTTP " + response.code() + "\n" + body);
            }
            String contentType = response.header("Content-Type", "");
            if (contentType.contains("text/event-stream")) {
                return parseSse(body, message.path("id").asText());
            }
            return mapper.readTree(body);
        }
    }

    @Override
    public void notify(JsonNode message) throws Exception {
        try (Response response = httpClient.newCall(requestBuilder(message).build()).execute()) {
            captureSession(response);
            if (!response.isSuccessful()) {
                String body = response.body() == null ? "" : response.body().string();
                logs.add("HTTP notification failed: " + response.code() + " " + body);
            }
        }
    }

    @Override
    public List<String> logs() {
        return logs.lines();
    }

    @Override
    public Long pid() {
        return null;
    }

    @Override
    public void close() {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Request.Builder builder = new Request.Builder()
            .url(config.url())
            .delete()
            .header("MCP-Protocol-Version", McpProtocol.VERSION)
            .header("Mcp-Session-Id", sessionId);
        config.headers().forEach(builder::header);
        try (Response response = httpClient.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                logs.add("HTTP session close failed: " + response.code());
            }
        } catch (Exception e) {
            logs.add("HTTP session close failed: " + e.getMessage());
        }
    }

    private Request.Builder requestBuilder(JsonNode message) {
        Request.Builder builder = new Request.Builder()
            .url(config.url())
            .post(RequestBody.create(message.toString(), JSON))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", McpProtocol.VERSION);
        if (sessionId != null && !sessionId.isBlank()) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        config.headers().forEach(builder::header);
        return builder;
    }

    private void captureSession(Response response) {
        String value = response.header("Mcp-Session-Id");
        if (value != null && !value.isBlank()) {
            sessionId = value;
        }
    }

    private JsonNode parseSse(String body, String requestId) throws Exception {
        List<JsonNode> messages = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\\r?\\n")) {
            if (line.isBlank()) {
                addSseMessage(data, messages);
                data.setLength(0);
                continue;
            }
            if (line.startsWith("data:")) {
                data.append(line.substring("data:".length()).trim());
            }
        }
        addSseMessage(data, messages);
        for (JsonNode message : messages) {
            if (requestId.equals(message.path("id").asText())) {
                return message;
            }
        }
        if (messages.isEmpty()) {
            throw new IllegalStateException("HTTP MCP SSE 响应为空");
        }
        return messages.get(messages.size() - 1);
    }

    private void addSseMessage(StringBuilder data, List<JsonNode> messages) throws Exception {
        String payload = data.toString().trim();
        if (payload.isBlank() || "[DONE]".equals(payload)) {
            return;
        }
        messages.add(mapper.readTree(payload));
    }
}
