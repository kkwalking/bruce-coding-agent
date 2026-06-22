package com.brucecli.mcp.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpConfigLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsUserAndProjectConfigWithProjectOverrideAndVariables() throws Exception {
        String originalHome = System.getProperty("user.home");
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("project");
        Files.createDirectories(home.resolve(".brucecli"));
        Files.createDirectories(workspace.resolve(".brucecli"));
        Files.writeString(workspace.resolve(".env"), "GLM_API_KEY=glm-from-env\n");
        Files.writeString(home.resolve(".brucecli/mcp.json"), """
            {
              "mcpServers": {
                "filesystem": {
                  "command": "npx",
                  "args": ["-y", "server", "${HOME}"]
                },
                "zread": {
                  "url": "https://example.com/mcp",
                  "headers": {"Authorization": "Bearer ${GLM_API_KEY}"}
                }
              }
            }
            """);
        Files.writeString(workspace.resolve(".brucecli/mcp.json"), """
            {
              "mcpServers": {
                "filesystem": {
                  "command": "node",
                  "args": ["${PROJECT_DIR}/server.js"]
                }
              }
            }
            """);

        try {
            System.setProperty("user.home", home.toString());
            McpConfig config = new McpConfigLoader(workspace).load();

            assertEquals(2, config.servers().size());
            McpServerConfig filesystem = config.servers().stream()
                .filter(server -> server.name().equals("filesystem"))
                .findFirst()
                .orElseThrow();
            McpServerConfig zread = config.servers().stream()
                .filter(server -> server.name().equals("zread"))
                .findFirst()
                .orElseThrow();
            assertEquals("node", filesystem.command());
            assertEquals(workspace.resolve("server.js").toString(), filesystem.args().get(0));
            assertEquals(McpTransportType.HTTP, zread.type());
            assertEquals("Bearer glm-from-env", zread.headers().get("Authorization"));
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void ignoresParentDotenvWhenResolvingVariables() throws Exception {
        String originalHome = System.getProperty("user.home");
        Path home = tempDir.resolve("home-parent-env");
        Path parent = tempDir.resolve("parent");
        Path workspace = parent.resolve("project");
        Files.createDirectories(home.resolve(".brucecli"));
        Files.createDirectories(workspace.resolve(".brucecli"));
        Files.writeString(parent.resolve(".env"), "GLM_API_KEY=glm-from-parent\n");
        Files.writeString(workspace.resolve(".brucecli/mcp.json"), """
            {
              "mcpServers": {
                "zread": {
                  "url": "https://example.com/mcp",
                  "headers": {"Authorization": "Bearer ${GLM_API_KEY}"}
                }
              }
            }
            """);

        try {
            System.setProperty("user.home", home.toString());
            McpConfig config = new McpConfigLoader(workspace).load();

            McpServerConfig zread = config.servers().get(0);
            assertEquals("Bearer ", zread.headers().get("Authorization"));
        } finally {
            System.setProperty("user.home", originalHome);
        }
    }
}
