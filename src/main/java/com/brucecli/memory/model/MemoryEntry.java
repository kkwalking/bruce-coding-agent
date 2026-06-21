package com.brucecli.memory.model;

import com.brucecli.memory.core.TokenEstimator;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 记忆的基本单元。
 *
 * <p>文章里强调：记忆不能只是一堆字符串。
 * Agent 需要知道每条记忆的类型、产生时间、token 数和附加元数据，
 * 这样后续才能做预算控制、时间衰减、检索排序和上下文压缩。</p>
 */
public final class MemoryEntry {
    private final String id;
    private final String content;
    private final MemoryType type;
    private final Instant timestamp;
    private final Map<String, String> metadata;
    private final int tokenCount;

    public MemoryEntry(
        String id,
        String content,
        MemoryType type,
        Instant timestamp,
        Map<String, String> metadata,
        int tokenCount
    ) {
        this.id = requireText(id, "id");
        this.content = requireText(content, "content");
        this.type = Objects.requireNonNull(type, "type");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        this.metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
        this.tokenCount = Math.max(0, tokenCount);
    }

    public static MemoryEntry of(String content, MemoryType type) {
        return of(content, type, Map.of());
    }

    public static MemoryEntry of(String content, MemoryType type, Map<String, String> metadata) {
        return new MemoryEntry(
            "mem_" + UUID.randomUUID().toString().substring(0, 8),
            content,
            type,
            Instant.now(),
            metadata,
            TokenEstimator.estimateTokens(content)
        );
    }

    public static MemoryEntry conversation(String role, String content) {
        return of(content, MemoryType.CONVERSATION, Map.of("role", role));
    }

    public static MemoryEntry fact(String content) {
        return of(content, MemoryType.FACT);
    }

    public static MemoryEntry summary(String content, Map<String, String> metadata) {
        return of(content, MemoryType.SUMMARY, metadata);
    }

    public static MemoryEntry toolResult(String toolName, String content) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("role", "tool");
        metadata.put("tool", toolName);
        return of(content, MemoryType.TOOL_RESULT, metadata);
    }

    public String id() {
        return id;
    }

    public String content() {
        return content;
    }

    public MemoryType type() {
        return type;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    public int tokenCount() {
        return tokenCount;
    }

    public String role() {
        return metadata.getOrDefault("role", "memory");
    }

    public String toPromptLine() {
        return "[" + type + "/" + role() + "] " + content;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空");
        }
        return value;
    }
}
