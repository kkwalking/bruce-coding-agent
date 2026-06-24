package com.brucecli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeepSeekClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesMultimodalMessageContentAsArray() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                {"choices":[{"message":{"content":"ok"}}]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            DeepSeekClient client = new DeepSeekClient(
                "test-key",
                "deepseek-v4-flash",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                new OkHttpClient()
            );
            Message message = Message.user(List.of(
                ContentPart.text("帮我分析下这张截图"),
                ContentPart.imageUrl("data:image/png;base64,AAAA", "[已附加图片: shot.png]")
            ));

            ChatResponse response = client.chat(List.of(message), List.of());

            assertEquals("ok", response.content());
            JsonNode root = mapper.readTree(requestBody.get());
            JsonNode content = root.path("messages").get(0).path("content");
            assertTrue(content.isArray());
            assertEquals("text", content.get(0).path("type").asText());
            assertEquals("帮我分析下这张截图", content.get(0).path("text").asText());
            assertEquals("image_url", content.get(1).path("type").asText());
            assertEquals("data:image/png;base64,AAAA", content.get(1).path("image_url").path("url").asText());
            assertTrue(root.has("thinking"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void serializesPlainTextContentAsString() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                {"choices":[{"message":{"content":"ok"}}]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            DeepSeekClient client = new DeepSeekClient(
                "test-key",
                "deepseek-v4-flash",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                new OkHttpClient()
            );

            client.chat(List.of(Message.user("hello bruce")), List.of());

            JsonNode root = mapper.readTree(requestBody.get());
            assertEquals("hello bruce", root.path("messages").get(0).path("content").asText());
            assertTrue(root.has("thinking"));
        } finally {
            server.stop(0);
        }
    }
}
