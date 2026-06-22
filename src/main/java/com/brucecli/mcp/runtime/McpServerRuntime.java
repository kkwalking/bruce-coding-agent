package com.brucecli.mcp.runtime;

import com.brucecli.mcp.config.McpServerConfig;
import com.brucecli.mcp.protocol.McpClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

class McpServerRuntime {
    private final McpServerConfig config;
    private McpClient client;
    private McpServerState state;
    private String error;
    private Instant startedAt;
    private List<McpToolDescriptor> tools = List.of();

    McpServerRuntime(McpServerConfig config) {
        this.config = config;
        this.state = config.disabled() ? McpServerState.DISABLED : McpServerState.ERROR;
        this.error = config.disabled() ? "配置已禁用" : "尚未启动";
    }

    McpServerConfig config() {
        return config;
    }

    McpClient client() {
        return client;
    }

    void ready(McpClient client, List<McpToolDescriptor> tools) {
        close();
        this.client = client;
        this.tools = tools == null ? List.of() : List.copyOf(tools);
        this.state = McpServerState.READY;
        this.error = "";
        this.startedAt = Instant.now();
    }

    void error(String error) {
        close();
        this.state = McpServerState.ERROR;
        this.error = error == null ? "未知错误" : error;
        this.tools = List.of();
        this.startedAt = null;
    }

    void disabled(String reason) {
        close();
        this.state = McpServerState.DISABLED;
        this.error = reason == null ? "已禁用" : reason;
        this.tools = List.of();
        this.startedAt = null;
    }

    McpServerStatus status() {
        Duration uptime = startedAt == null ? Duration.ZERO : Duration.between(startedAt, Instant.now());
        return new McpServerStatus(
            config.name(),
            state,
            config.type(),
            tools.size(),
            uptime,
            client == null ? null : client.pid(),
            error
        );
    }

    List<McpToolDescriptor> tools() {
        return tools;
    }

    String logs() {
        if (client == null) {
            return error == null || error.isBlank() ? "暂无日志。" : error;
        }
        return client.logs();
    }

    void close() {
        if (client != null) {
            client.close();
            client = null;
        }
    }
}
