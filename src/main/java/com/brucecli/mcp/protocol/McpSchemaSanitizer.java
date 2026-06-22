package com.brucecli.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Map;

public class McpSchemaSanitizer {
    private static final int DESCRIPTION_LIMIT = 1_000;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonNode sanitize(JsonNode schema) {
        JsonNode source = schema == null || schema.isMissingNode() || schema.isNull()
            ? mapper.createObjectNode()
            : schema.deepCopy();
        ObjectNode objectSchema = ensureObjectSchema(source);
        sanitizeObject(objectSchema);
        if (!objectSchema.has("properties") || !objectSchema.path("properties").isObject()) {
            objectSchema.set("properties", mapper.createObjectNode());
        }
        if (!objectSchema.has("required") || !objectSchema.path("required").isArray()) {
            objectSchema.set("required", mapper.createArrayNode());
        }
        return objectSchema;
    }

    private ObjectNode ensureObjectSchema(JsonNode schema) {
        if (!schema.isObject()) {
            ObjectNode wrapper = mapper.createObjectNode();
            wrapper.put("type", "object");
            ObjectNode properties = wrapper.putObject("properties");
            properties.set("value", schema);
            return wrapper;
        }

        ObjectNode object = (ObjectNode) schema;
        String type = object.path("type").asText("");
        if (type.isBlank()) {
            object.put("type", "object");
            return object;
        }
        if (!"object".equals(type)) {
            ObjectNode wrapper = mapper.createObjectNode();
            wrapper.put("type", "object");
            ObjectNode properties = wrapper.putObject("properties");
            properties.set("value", object);
            return wrapper;
        }
        return object;
    }

    private void sanitizeObject(ObjectNode node) {
        node.remove("$schema");
        node.remove("$id");
        node.remove("$defs");
        node.remove("definitions");
        node.remove("$ref");
        foldUnion(node, "anyOf");
        foldUnion(node, "oneOf");
        truncateDescription(node);

        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            if (value.isObject()) {
                sanitizeObject((ObjectNode) value);
            } else if (value.isArray()) {
                sanitizeArray((ArrayNode) value);
            }
        }
    }

    private void sanitizeArray(ArrayNode array) {
        for (JsonNode item : array) {
            if (item.isObject()) {
                sanitizeObject((ObjectNode) item);
            } else if (item.isArray()) {
                sanitizeArray((ArrayNode) item);
            }
        }
    }

    private void foldUnion(ObjectNode node, String fieldName) {
        JsonNode union = node.get(fieldName);
        if (union == null || !union.isArray()) {
            return;
        }
        StringBuilder description = new StringBuilder(node.path("description").asText(""));
        if (!description.isEmpty()) {
            description.append('\n');
        }
        description.append(fieldName).append(" options: ");
        for (JsonNode option : union) {
            String type = option.path("type").asText("");
            String optionDescription = option.path("description").asText("");
            if (!type.isBlank()) {
                description.append(type);
            }
            if (!optionDescription.isBlank()) {
                description.append("(").append(optionDescription).append(")");
            }
            description.append("; ");
        }
        node.put("description", truncate(description.toString()));
        node.remove(fieldName);
    }

    private void truncateDescription(ObjectNode node) {
        JsonNode description = node.get("description");
        if (description != null && description.isTextual()) {
            node.put("description", truncate(description.asText()));
        }
    }

    private String truncate(String text) {
        if (text == null || text.length() <= DESCRIPTION_LIMIT) {
            return text == null ? "" : text;
        }
        return text.substring(0, DESCRIPTION_LIMIT) + "...";
    }
}
