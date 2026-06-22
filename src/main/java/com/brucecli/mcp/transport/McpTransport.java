package com.brucecli.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;

public interface McpTransport extends AutoCloseable {
    void start() throws Exception;

    JsonNode request(JsonNode message, Duration timeout) throws Exception;

    void notify(JsonNode message) throws Exception;

    List<String> logs();

    Long pid();

    @Override
    void close();
}
