package com.brucecli.mcp.runtime;

import com.brucecli.mcp.config.McpTransportType;

import java.time.Duration;

public record McpServerStatus(
    String name,
    McpServerState state,
    McpTransportType transportType,
    int toolCount,
    Duration uptime,
    Long pid,
    String error
) {
    public String toDisplayLine() {
        String marker = switch (state) {
            case READY -> "ready";
            case ERROR -> "error";
            case DISABLED -> "disabled";
        };
        String pidText = pid == null ? "-" : String.valueOf(pid);
        String errorText = error == null || error.isBlank() ? "" : " | " + error;
        return "%s | %s | %s | %d tools | %ss | PID %s%s".formatted(
            name,
            marker,
            transportType.name().toLowerCase(),
            toolCount,
            uptime == null ? 0 : uptime.toSeconds(),
            pidText,
            errorText
        );
    }
}
