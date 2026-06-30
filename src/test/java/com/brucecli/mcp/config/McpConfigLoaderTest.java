package com.brucecli.mcp.config;

import com.brucecli.config.BruceSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpConfigLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsMcpServersFromSettingsAndResolvesVariables() throws Exception {
        String originalHome = System.getProperty("user.home");
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("project");
        Path settingsFile = home.resolve(".bruce/setting.json");
        Files.createDirectories(settingsFile.getParent());
        Files.writeString(settingsFile, "{}");

        BruceSettings settings = new BruceSettings();
        settings.getVariables().put("demoToken", "token-from-variable");
        BruceSettings.ProviderSettings glm = new BruceSettings.ProviderSettings();
        glm.setApiKey("glm-from-settings");
        settings.getLlm().getProviders().put("glm", glm);

        BruceSettings.McpServerSettings filesystem = new BruceSettings.McpServerSettings();
        filesystem.setCommand("npx");
        filesystem.setArgs(List.of("-y", "server", "${PROJECT_DIR}", "${HOME}"));
        filesystem.setEnv(Map.of(
            "TOKEN", "${variables.demoToken}",
            "DIRECT_TOKEN", "${demoToken}"
        ));

        BruceSettings.McpServerSettings zread = new BruceSettings.McpServerSettings();
        zread.setType("streamableHttp");
        zread.setUrl("https://example.com/mcp");
        zread.setHeaders(Map.of("Authorization", "Bearer ${llm.providers.glm.apiKey}"));

        settings.getMcp().getServers().put("filesystem", filesystem);
        settings.getMcp().getServers().put("zread", zread);

        try {
            System.setProperty("user.home", home.toString());
            McpConfig config = new McpConfigLoader(workspace, settings, settingsFile).load();

            assertEquals(List.of(settingsFile.toAbsolutePath().normalize()), config.loadedFiles());
            assertEquals(2, config.servers().size());
            McpServerConfig filesystemConfig = config.servers().stream()
                .filter(server -> server.name().equals("filesystem"))
                .findFirst()
                .orElseThrow();
            McpServerConfig zreadConfig = config.servers().stream()
                .filter(server -> server.name().equals("zread"))
                .findFirst()
                .orElseThrow();

            assertEquals(McpTransportType.STDIO, filesystemConfig.type());
            assertEquals("npx", filesystemConfig.command());
            assertEquals(workspace.toAbsolutePath().normalize().toString(), filesystemConfig.args().get(2));
            assertEquals(home.toString(), filesystemConfig.args().get(3));
            assertEquals("token-from-variable", filesystemConfig.env().get("TOKEN"));
            assertEquals("token-from-variable", filesystemConfig.env().get("DIRECT_TOKEN"));
            assertEquals(McpTransportType.HTTP, zreadConfig.type());
            assertEquals("Bearer glm-from-settings", zreadConfig.headers().get("Authorization"));
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void ignoresLegacyMcpJsonFiles() throws Exception {
        Path workspace = tempDir.resolve("project-with-legacy");
        Files.createDirectories(workspace.resolve(".bruce"));
        Files.writeString(workspace.resolve(".bruce/mcp.json"), """
            {
              "mcpServers": {
                "legacy": {
                  "command": "node"
                }
              }
            }
            """);

        McpConfig config = new McpConfigLoader(workspace, new BruceSettings(), tempDir.resolve("setting.json")).load();

        assertTrue(config.servers().isEmpty());
    }

    @Test
    void unresolvedVariablesBecomeEmptyStrings() throws Exception {
        Path workspace = tempDir.resolve("project-missing-variable");
        BruceSettings settings = new BruceSettings();
        BruceSettings.McpServerSettings zread = new BruceSettings.McpServerSettings();
        zread.setUrl("https://example.com/mcp");
        zread.setHeaders(Map.of("Authorization", "Bearer ${missing.value}"));
        settings.getMcp().getServers().put("zread", zread);

        McpConfig config = new McpConfigLoader(workspace, settings, tempDir.resolve("setting.json")).load();

        assertEquals("Bearer ", config.servers().get(0).headers().get("Authorization"));
    }
}
