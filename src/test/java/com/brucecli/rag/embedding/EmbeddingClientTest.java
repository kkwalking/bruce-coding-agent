package com.brucecli.rag.embedding;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingClientTest {
    @Test
    void ollamaProviderUsesEmbedEndpoint() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                {"model":"nomic-embed-text:latest","embeddings":[[0.1,0.2,0.3]]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(response);
            }
        });
        server.start();
        try {
            EmbeddingClient client = new EmbeddingClient(
                "ollama",
                "nomic-embed-text:latest",
                "http://127.0.0.1:" + server.getAddress().getPort(),
                ""
            );

            float[] embedding = client.embed("hello agent");

            assertArrayEquals(new float[] {0.1f, 0.2f, 0.3f}, embedding, 0.0001f);
            assertEquals("/api/embed", requestPath.get());
            assertTrue(requestBody.get().contains("\"model\":\"nomic-embed-text:latest\""));
            assertTrue(requestBody.get().contains("\"input\":\"hello agent\""));
        } finally {
            server.stop(0);
        }
    }
}
