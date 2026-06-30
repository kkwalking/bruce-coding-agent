package com.brucecli.mcp.config;

import com.brucecli.config.BruceSettings;
import com.brucecli.config.BruceSettingsLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读取 bruce-coding-agent MCP 配置。
 *
 * <p>MCP server 统一配置在 ~/.bruce/setting.json 的 mcp.servers 中。</p>
 */
public class McpConfigLoader {
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path workspaceRoot;
    private final BruceSettingsLoader settingsLoader;
    private final BruceSettings settingsOverride;
    private final Path settingsFileOverride;

    public McpConfigLoader(Path workspaceRoot) {
        this(workspaceRoot, BruceSettingsLoader.defaults());
    }

    public McpConfigLoader(Path workspaceRoot, BruceSettingsLoader settingsLoader) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.settingsLoader = settingsLoader == null ? BruceSettingsLoader.defaults() : settingsLoader;
        this.settingsOverride = null;
        this.settingsFileOverride = null;
    }

    public McpConfigLoader(Path workspaceRoot, BruceSettings settings, Path settingsFile) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.settingsLoader = null;
        this.settingsOverride = settings == null ? new BruceSettings() : settings;
        this.settingsFileOverride = settingsFile == null ? null : settingsFile.toAbsolutePath().normalize();
    }

    public McpConfig load() throws IOException {
        BruceSettings settings = loadSettings();
        JsonNode settingsTree = mapper.valueToTree(settings);
        Map<String, McpServerConfig> servers = new LinkedHashMap<>();
        settings.getMcp().getServers().forEach((name, server) ->
            servers.put(name, parseServer(name, server, settings, settingsTree))
        );
        return new McpConfig(new ArrayList<>(servers.values()), loadedFiles());
    }

    private BruceSettings loadSettings() throws IOException {
        if (settingsOverride != null) {
            return settingsOverride;
        }
        return settingsLoader.load();
    }

    private List<Path> loadedFiles() {
        Path file = settingsFile();
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        return List.of(file);
    }

    private Path settingsFile() {
        if (settingsFileOverride != null) {
            return settingsFileOverride;
        }
        return settingsLoader == null ? null : settingsLoader.settingsFile();
    }

    private McpServerConfig parseServer(
        String name,
        BruceSettings.McpServerSettings node,
        BruceSettings settings,
        JsonNode settingsTree
    ) {
        BruceSettings.McpServerSettings server = node == null ? new BruceSettings.McpServerSettings() : node;
        return new McpServerConfig(
            name,
            detectType(server),
            resolve(server.getCommand(), settings, settingsTree),
            resolveList(server.getArgs(), settings, settingsTree),
            resolveMap(server.getEnv(), settings, settingsTree),
            resolve(server.getUrl(), settings, settingsTree),
            resolveMap(server.getHeaders(), settings, settingsTree),
            server.isDisabled()
        );
    }

    private McpTransportType detectType(BruceSettings.McpServerSettings server) {
        String rawType = server.getType() == null ? "" : server.getType();
        String normalized = rawType
            .replace("-", "")
            .replace("_", "")
            .toLowerCase(Locale.ROOT);
        if (normalized.equals("http") || normalized.equals("streamablehttp")) {
            return McpTransportType.HTTP;
        }
        String url = server.getUrl();
        if (url != null && !url.isBlank()) {
            return McpTransportType.HTTP;
        }
        return McpTransportType.STDIO;
    }

    private List<String> resolveList(List<String> values, BruceSettings settings, JsonNode settingsTree) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> resolved = new ArrayList<>();
        for (String value : values) {
            resolved.add(resolve(value, settings, settingsTree));
        }
        return resolved;
    }

    private Map<String, String> resolveMap(Map<String, String> values, BruceSettings settings, JsonNode settingsTree) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        values.forEach((key, value) -> resolved.put(key, resolve(value, settings, settingsTree)));
        return resolved;
    }

    private String resolve(String raw, BruceSettings settings, JsonNode settingsTree) {
        if (raw == null || raw.isBlank()) {
            return raw == null ? "" : raw;
        }
        Matcher matcher = VARIABLE.matcher(raw);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String replacement = variableValue(matcher.group(1), settings, settingsTree);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String variableValue(String name, BruceSettings settings, JsonNode settingsTree) {
        if ("PROJECT_DIR".equals(name)) {
            return workspaceRoot.toString();
        }
        if ("HOME".equals(name)) {
            return System.getProperty("user.home");
        }
        if (name.startsWith("variables.")) {
            return settings.getVariables().getOrDefault(name.substring("variables.".length()), "");
        }
        String variable = settings.getVariables().get(name);
        if (variable != null) {
            return variable;
        }
        String settingsValue = settingsPathValue(name, settingsTree);
        return settingsValue == null ? "" : settingsValue;
    }

    private String settingsPathValue(String path, JsonNode settingsTree) {
        JsonNode node = settingsTree;
        for (String segment : path.split("\\.")) {
            if (segment.isBlank() || node == null || !node.isObject()) {
                return null;
            }
            node = node.get(segment);
            if (node == null || node.isMissingNode()) {
                return null;
            }
        }
        if (node.isValueNode()) {
            return node.asText();
        }
        return node.toString();
    }
}
