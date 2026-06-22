package com.brucecli.mcp.runtime;

import com.fasterxml.jackson.databind.JsonNode;

public record McpToolDescriptor(
    String serverName,
    String toolName,
    String registeredName,
    String description,
    JsonNode inputSchema
) {
}
