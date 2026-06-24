package com.brucecli.mcp.protocol;

import com.brucecli.mcp.transport.McpTransport;
import com.brucecli.tool.ToolResultContent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class McpClient implements AutoCloseable {
    private static final Duration INITIALIZE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);

    private final String serverName;
    private final McpTransport transport;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong nextId = new AtomicLong(1);

    public McpClient(String serverName, McpTransport transport) {
        this.serverName = serverName;
        this.transport = transport;
    }

    public void start() throws Exception {
        transport.start();
        initialize();
        notifyInitialized();
    }

    public List<McpTool> listTools() throws Exception {
        JsonNode result = request("tools/list", mapper.createObjectNode(), CALL_TIMEOUT);
        JsonNode tools = result.path("tools");
        List<McpTool> parsed = new ArrayList<>();
        if (!tools.isArray()) {
            return parsed;
        }
        for (JsonNode tool : tools) {
            parsed.add(new McpTool(
                tool.path("name").asText(""),
                tool.path("description").asText(""),
                tool.path("inputSchema")
            ));
        }
        return parsed;
    }

    public String callTool(String name, JsonNode arguments) throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null || arguments.isMissingNode() ? mapper.createObjectNode() : arguments);
        JsonNode result = request("tools/call", params, CALL_TIMEOUT);
        return formatToolResult(name, result);
    }

    public Long pid() {
        return transport.pid();
    }

    public String logs() {
        List<String> lines = transport.logs();
        return lines.isEmpty() ? "暂无日志。" : String.join("\n", lines);
    }

    @Override
    public void close() {
        transport.close();
    }

    private void initialize() throws Exception {
        ObjectNode capabilities = mapper.createObjectNode();
        capabilities.putObject("tools");
        ObjectNode clientInfo = mapper.createObjectNode();
        clientInfo.put("name", "bruce-cli");
        clientInfo.put("version", "1.0.0");

        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", McpProtocol.VERSION);
        params.set("capabilities", capabilities);
        params.set("clientInfo", clientInfo);
        request("initialize", params, INITIALIZE_TIMEOUT);
    }

    private void notifyInitialized() throws Exception {
        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        notification.set("params", mapper.createObjectNode());
        transport.notify(notification);
    }

    private JsonNode request(String method, JsonNode params, Duration timeout) throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", nextId.getAndIncrement());
        request.put("method", method);
        request.set("params", params == null ? mapper.createObjectNode() : params);

        JsonNode response = transport.request(request, timeout);
        JsonNode error = response.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new McpException("MCP " + serverName + " 调用失败: " + error);
        }
        JsonNode result = response.path("result");
        if (result.isMissingNode()) {
            throw new McpException("MCP " + serverName + " 响应缺少 result: " + response);
        }
        return result;
    }

    private String formatToolResult(String toolName, JsonNode result) {
        JsonNode content = result.path("content");
        if (!content.isArray()) {
            return result.toPrettyString();
        }

        List<String> chunks = new ArrayList<>();
        for (JsonNode item : content) {
            String type = item.path("type").asText("");
            if ("text".equals(type)) {
                chunks.add(item.path("text").asText(""));
            } else if ("image".equals(type)) {
                String data = item.path("data").asText("");
                String mimeType = item.path("mimeType").asText("image/png");
                if (data.isBlank()) {
                    chunks.add("[MCP 图片内容缺少 base64 data]");
                } else {
                    chunks.add("[MCP 图片内容: " + mimeType + "]");
                    chunks.add(ToolResultContent.encodeImage(
                        mimeType,
                        data,
                        "mcp:" + serverName + "/" + toolName
                    ));
                }
            } else {
                chunks.add("[非文本 MCP 内容: " + (type.isBlank() ? "unknown" : type) + "]");
            }
        }
        return String.join("\n", chunks).trim();
    }

    public JsonNode mapToArguments(Map<String, String> args) {
        ObjectNode node = mapper.createObjectNode();
        if (args == null) {
            return node;
        }
        args.forEach((key, value) -> node.set(key, parseScalar(value)));
        return node;
    }

    private JsonNode parseScalar(String value) {
        if (value == null) {
            return mapper.nullNode();
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[") || "true".equals(trimmed)
            || "false".equals(trimmed) || "null".equals(trimmed) || trimmed.matches("-?\\d+(\\.\\d+)?")) {
            try {
                return mapper.readTree(trimmed);
            } catch (Exception ignored) {
                // 回退成字符串。
            }
        }
        return mapper.getNodeFactory().textNode(value);
    }
}
