package com.brucecli.mcp.config;

import java.nio.file.Path;
import java.util.List;

public record McpConfig(List<McpServerConfig> servers, List<Path> loadedFiles) {
    public McpConfig {
        servers = servers == null ? List.of() : List.copyOf(servers);
        loadedFiles = loadedFiles == null ? List.of() : List.copyOf(loadedFiles);
    }

    public boolean configured() {
        return !servers.isEmpty();
    }
}
