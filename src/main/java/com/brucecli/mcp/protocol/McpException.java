package com.brucecli.mcp.protocol;

public class McpException extends Exception {
    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }
}
