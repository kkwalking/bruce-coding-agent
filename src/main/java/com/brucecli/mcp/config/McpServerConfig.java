package com.brucecli.mcp.config;

import java.util.List;
import java.util.Map;

public record McpServerConfig(
    String name,
    McpTransportType type,
    String command,
    List<String> args,
    Map<String, String> env,
    String url,
    Map<String, String> headers,
    boolean disabled
) {
    public McpServerConfig {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("MCP server 名称不能为空");
        }
        type = type == null ? McpTransportType.STDIO : type;
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        command = command == null ? "" : command.trim();
        url = url == null ? "" : url.trim();
    }
}
