package com.brucecli.mcp.transport;

import com.brucecli.mcp.config.McpServerConfig;
import com.brucecli.mcp.config.McpTransportType;

import java.nio.file.Path;

public class McpTransportFactory {
    public McpTransport create(McpServerConfig config, Path workspaceRoot) {
        if (config.type() == McpTransportType.HTTP) {
            return new StreamableHttpMcpTransport(config);
        }
        return new StdioMcpTransport(config, workspaceRoot);
    }
}
