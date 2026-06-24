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

class GlmClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void selectsProviderSpecificDefaultEndpoints() {
        GlmClient codingClient = new GlmClient("key", "glm-5.1");
        GlmClient visionClient = new GlmClient("key", "glm-5v");

        assertEquals(GlmClient.CODING_API_URL, codingClient.apiUrl);
        assertEquals(GlmClient.MULTIMODAL_API_URL, visionClient.apiUrl);
    }

    @Test
    void glm5vSendsRawBase64ImageUrl() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                {"choices":[{"message":{"role":"assistant","content":"ok"}}]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            GlmClient client = new GlmClient(
                "test-key",
                "glm-5v",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                new OkHttpClient()
            );
            Message message = Message.user(List.of(
                ContentPart.text("描述图片"),
                ContentPart.imageUrl("data:image/png;base64,AAAA", "[已附加图片: shot.png]")
            ));

            ChatResponse response = client.chat(List.of(message), List.of());

            assertEquals("ok", response.content());
            JsonNode root = mapper.readTree(requestBody.get());
            JsonNode imageUrl = root.path("messages").get(0).path("content").get(1).path("image_url");
            assertEquals("AAAA", imageUrl.path("url").asText());
            assertFalse(root.has("thinking"));
        } finally {
            server.stop(0);
        }
    }
}
