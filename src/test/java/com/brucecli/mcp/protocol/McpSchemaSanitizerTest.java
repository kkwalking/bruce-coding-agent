package com.brucecli.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSchemaSanitizerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void removesUnsupportedFieldsAndFoldsUnionDescriptions() throws Exception {
        JsonNode input = mapper.readTree("""
            {
              "$schema": "https://json-schema.org",
              "$id": "tool",
              "properties": {
                "path": {
                  "anyOf": [
                    {"type": "string", "description": "file path"},
                    {"type": "null"}
                  ]
                }
              }
            }
            """);

        JsonNode sanitized = new McpSchemaSanitizer().sanitize(input);

        assertEquals("object", sanitized.path("type").asText());
        assertFalse(sanitized.has("$schema"));
        assertFalse(sanitized.has("$id"));
        assertFalse(sanitized.path("properties").path("path").has("anyOf"));
        assertTrue(sanitized.path("properties").path("path").path("description").asText().contains("anyOf options"));
    }

    @Test
    void wrapsNonObjectSchemas() throws Exception {
        JsonNode sanitized = new McpSchemaSanitizer().sanitize(mapper.readTree("""
            {"type": "string", "description": "raw input"}
            """));

        assertEquals("object", sanitized.path("type").asText());
        assertTrue(sanitized.path("properties").has("value"));
    }
}
