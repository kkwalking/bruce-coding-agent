package com.brucecli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleChatClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesNullContentReasoningToolCallsAndUsage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] response = """
                {
                  "choices": [
                    {
                      "message": {
                        "role": "assistant",
                        "content": null,
                        "reasoning_content": "先读文件",
                        "tool_calls": [
                          {
                            "id": "call-1",
                            "type": "function",
                            "function": {
                              "name": "read_file",
                              "arguments": "{\\"path\\":\\"pom.xml\\"}"
                            }
                          }
                        ]
                      }
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 12,
                    "completion_tokens": 5,
                    "prompt_tokens_details": {
                      "cached_tokens": 3
                    }
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            TestClient client = new TestClient(endpoint(server));

            ChatResponse response = client.chat(List.of(Message.user("读一下 pom")), List.of());

            assertEquals("assistant", response.role());
            assertEquals("", response.content());
            assertEquals("先读文件", response.reasoningContent());
            assertEquals(12, response.inputTokens());
            assertEquals(5, response.outputTokens());
            assertEquals(3, response.cachedInputTokens());
            assertEquals("read_file", response.toolCalls().get(0).function().name());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void parsesStreamingReasoningContentToolCallsAndUsage() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                data: {"choices":[{"delta":{"role":"assistant","reasoning_content":"想","content":"答","tool_calls":[{"index":0,"id":"call-1","function":{"name":"read_","arguments":"{\\"path"}}]}}]}

                data: {"choices":[{"delta":{"reasoning_content":"一下","content":"案","tool_calls":[{"index":0,"function":{"name":"file","arguments":"\\":\\"pom.xml\\"}"}}]}}]}

                data: {"choices":[],"usage":{"prompt_tokens":20,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":6}}}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            TestClient client = new TestClient(endpoint(server));
            StringBuilder reasoning = new StringBuilder();
            StringBuilder content = new StringBuilder();

            ChatResponse response = client.chat(
                List.of(Message.user("hi")),
                List.of(),
                new ChatClient.StreamListener() {
                    @Override
                    public void onReasoningDelta(String delta) {
                        reasoning.append(delta);
                    }

                    @Override
                    public void onContentDelta(String delta) {
                        content.append(delta);
                    }
                }
            );

            JsonNode root = mapper.readTree(requestBody.get());
            assertTrue(root.path("stream").asBoolean());
            assertTrue(root.path("stream_options").path("include_usage").asBoolean());
            assertEquals("想一下", reasoning.toString());
            assertEquals("答案", content.toString());
            assertEquals("想一下", response.reasoningContent());
            assertEquals("答案", response.content());
            assertEquals(20, response.inputTokens());
            assertEquals(7, response.outputTokens());
            assertEquals(6, response.cachedInputTokens());
            assertEquals("call-1", response.toolCalls().get(0).id());
            assertEquals("read_file", response.toolCalls().get(0).function().name());
            assertEquals("{\"path\":\"pom.xml\"}", response.toolCalls().get(0).function().arguments());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void includesProviderNameInErrorResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            byte[] response = "{\"error\":\"bad\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            TestClient client = new TestClient(endpoint(server));

            IOException exception = assertThrows(
                IOException.class,
                () -> client.chat(List.of(Message.user("hi")), List.of())
            );
            assertTrue(exception.getMessage().contains("test API request failed: HTTP 400"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void omitsPromptOnlyToolMetadataFromOpenAiToolSchema() throws Exception {
        TestClient client = new TestClient("http://127.0.0.1:1/chat/completions");
        JsonNode parameters = mapper.createObjectNode().put("type", "object");
        ToolDefinition tool = new ToolDefinition(
            "read_file",
            "读取文件",
            parameters,
            "Read file contents",
            List.of("Use read_file for known paths.")
        );

        JsonNode request = client.requestBody(List.of(tool));
        JsonNode function = request.path("tools").get(0).path("function");

        assertEquals("read_file", function.path("name").asText());
        assertTrue(function.has("parameters"));
        assertFalse(function.has("promptSnippet"));
        assertFalse(function.has("promptGuidelines"));
    }

    private static String endpoint(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions";
    }

    private static class TestClient extends OpenAiCompatibleChatClient {
        TestClient(String apiUrl) {
            super("test-key", "test-model", apiUrl, new OkHttpClient());
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        JsonNode requestBody(List<ToolDefinition> tools) {
            return buildRequestBody(List.of(Message.user("hi")), tools, false);
        }
    }
}
