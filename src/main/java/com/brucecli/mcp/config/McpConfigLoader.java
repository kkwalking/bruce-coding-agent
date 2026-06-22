package com.brucecli.mcp.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读取 bruce-cli MCP 配置。
 *
 * <p>加载顺序：用户级 ~/.brucecli/mcp.json，再读项目级 .brucecli/mcp.json；
 * 同名 server 由项目级覆盖。</p>
 */
public class McpConfigLoader {
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path workspaceRoot;
    private final Map<String, String> dotenvValues;

    public McpConfigLoader(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.dotenvValues = loadDotenvValues(this.workspaceRoot);
    }

    public McpConfig load() throws IOException {
        Map<String, McpServerConfig> servers = new LinkedHashMap<>();
        List<Path> loadedFiles = new ArrayList<>();
        for (Path file : candidateFiles()) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            loadedFiles.add(file);
            readFile(file, servers);
        }
        return new McpConfig(new ArrayList<>(servers.values()), loadedFiles);
    }

    public List<Path> candidateFiles() {
        return List.of(
            Path.of(System.getProperty("user.home"), ".brucecli", "mcp.json"),
            workspaceRoot.resolve(".brucecli/mcp.json")
        );
    }

    private void readFile(Path file, Map<String, McpServerConfig> servers) throws IOException {
        JsonNode root = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
        JsonNode mcpServers = root.path("mcpServers");
        if (!mcpServers.isObject()) {
            return;
        }
        mcpServers.fields().forEachRemaining(entry -> {
            McpServerConfig config = parseServer(entry.getKey(), entry.getValue());
            servers.put(config.name(), config);
        });
    }

    private McpServerConfig parseServer(String name, JsonNode node) {
        McpTransportType type = detectType(node);
        List<String> args = new ArrayList<>();
        JsonNode argsNode = node.path("args");
        if (argsNode.isArray()) {
            for (JsonNode arg : argsNode) {
                args.add(resolve(arg.asText("")));
            }
        }

        return new McpServerConfig(
            name,
            type,
            resolve(node.path("command").asText("")),
            args,
            readStringMap(node.path("env")),
            resolve(node.path("url").asText("")),
            readStringMap(node.path("headers")),
            node.path("disabled").asBoolean(false)
        );
    }

    private McpTransportType detectType(JsonNode node) {
        String rawType = node.path("type").asText("");
        if (rawType.equalsIgnoreCase("http")
            || rawType.equalsIgnoreCase("streamable_http")
            || rawType.equalsIgnoreCase("streamable-http")) {
            return McpTransportType.HTTP;
        }
        if (node.hasNonNull("url")) {
            return McpTransportType.HTTP;
        }
        return McpTransportType.STDIO;
    }

    private Map<String, String> readStringMap(JsonNode node) {
        if (!node.isObject()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> values.put(entry.getKey(), resolve(entry.getValue().asText(""))));
        return values;
    }

    private String resolve(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw == null ? "" : raw;
        }
        Matcher matcher = VARIABLE.matcher(raw);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String replacement = variableValue(matcher.group(1));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String variableValue(String name) {
        if ("PROJECT_DIR".equals(name)) {
            return workspaceRoot.toString();
        }
        if ("HOME".equals(name)) {
            return System.getProperty("user.home");
        }
        String envValue = System.getenv(name);
        if (envValue != null) {
            return envValue;
        }
        String dotenvValue = dotenvValues.get(name);
        return dotenvValue == null ? "" : dotenvValue;
    }

    private Map<String, String> loadDotenvValues(Path workspaceRoot) {
        Map<String, String> values = new LinkedHashMap<>();
        Path file = workspaceRoot.resolve(".env");
        if (!Files.isRegularFile(file)) {
            return values;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                readDotenvLine(line, values);
            }
        } catch (IOException ignored) {
            // .env 只是变量替换辅助，读不到时保持配置加载可用。
        }
        return values;
    }

    private void readDotenvLine(String line, Map<String, String> values) {
        if (line == null) {
            return;
        }
        String trimmed = line.trim();
        if (trimmed.isBlank() || trimmed.startsWith("#")) {
            return;
        }
        if (trimmed.startsWith("export ")) {
            trimmed = trimmed.substring("export ".length()).trim();
        }
        int split = trimmed.indexOf('=');
        if (split <= 0) {
            return;
        }
        values.put(trimmed.substring(0, split).trim(), unquote(trimmed.substring(split + 1).trim()));
    }

    private String unquote(String value) {
        if (value.length() >= 2
            && ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
