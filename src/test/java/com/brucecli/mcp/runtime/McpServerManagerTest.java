package com.brucecli.mcp.runtime;

import com.brucecli.mcp.config.McpConfig;
import com.brucecli.mcp.config.McpServerConfig;
import com.brucecli.mcp.config.McpTransportType;
import com.brucecli.mcp.transport.McpTransport;
import com.brucecli.mcp.transport.McpTransportFactory;
import com.brucecli.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void startsServerRegistersNamespacedToolAndCallsIt() {
        FakeTransport transport = new FakeTransport();
        McpServerConfig server = new McpServerConfig(
            "fake",
            McpTransportType.STDIO,
            "fake",
            List.of(),
            Map.of(),
            "",
            Map.of(),
            false
        );
        McpServerManager manager = new McpServerManager(
            tempDir,
            new McpConfig(List.of(server), List.of()),
            new FakeTransportFactory(transport)
        );
        ToolRegistry registry = new ToolRegistry(tempDir);

        manager.startAll();
        manager.registerTools(registry);
        String result = registry.executeTool("mcp__fake__echo", Map.of("message", "hello"));

        assertTrue(registry.getToolNames().contains("mcp__fake__echo"));
        assertEquals("called: hello", result);
        assertEquals("echo", transport.lastToolName);
        assertTrue(manager.statusTable().contains("ready"));
        manager.close();
    }

    private static class FakeTransportFactory extends McpTransportFactory {
        private final McpTransport transport;

        private FakeTransportFactory(McpTransport transport) {
            this.transport = transport;
        }

        @Override
        public McpTransport create(McpServerConfig config, Path workspaceRoot) {
            return transport;
        }
    }

    private static class FakeTransport implements McpTransport {
        private final ObjectMapper mapper = new ObjectMapper();
        private String lastToolName;

        @Override
        public void start() {
        }

        @Override
        public JsonNode request(JsonNode message, Duration timeout) throws Exception {
            String method = message.path("method").asText();
            JsonNode params = message.path("params");
            JsonNode result = switch (method) {
                case "initialize" -> mapper.readTree("""
                    {"serverInfo":{"name":"fake","version":"1.0.0"}}
                    """);
                case "tools/list" -> mapper.readTree("""
                    {
                      "tools": [
                        {
                          "name": "echo",
                          "description": "Echo message",
                          "inputSchema": {
                            "type": "object",
                            "properties": {
                              "message": {"type": "string"}
                            },
                            "required": ["message"]
                          }
                        }
                      ]
                    }
                    """);
                case "tools/call" -> {
                    lastToolName = params.path("name").asText();
                    String text = "called: " + params.path("arguments").path("message").asText();
                    yield mapper.readTree("""
                        {"content":[{"type":"text","text":"%s"}]}
                        """.formatted(text));
                }
                default -> throw new IllegalArgumentException("unknown method: " + method);
            };
            return mapper.readTree("""
                {"jsonrpc":"2.0","id":%d,"result":%s}
                """.formatted(message.path("id").asLong(), result));
        }

        @Override
        public void notify(JsonNode message) {
        }

        @Override
        public List<String> logs() {
            return List.of("fake log");
        }

        @Override
        public Long pid() {
            return 123L;
        }

        @Override
        public void close() {
        }
    }
}
