package com.brucecli.llm;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 暴露给模型的工具定义。
 *
 * <p>这会被序列化成 Chat Completion 请求中的 tools[i].function。
 * parameters 是 JSON Schema，告诉模型这个工具接收哪些参数、哪些参数必填。</p>
 */
public record ToolDefinition(
    String name,
    String description,
    JsonNode parameters,
    String promptSnippet,
    List<String> promptGuidelines
) {
    public ToolDefinition {
        promptSnippet = promptSnippet == null ? "" : promptSnippet;
        promptGuidelines = promptGuidelines == null ? List.of() : List.copyOf(promptGuidelines);
    }

    public ToolDefinition(String name, String description, JsonNode parameters) {
        this(name, description, parameters, "", List.of());
    }
}
