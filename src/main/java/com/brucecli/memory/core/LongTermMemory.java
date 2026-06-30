package com.brucecli.memory.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.brucecli.memory.model.MemoryEntry;
import com.brucecli.memory.model.MemoryType;
import com.brucecli.memory.retrieval.MemoryRetriever;
import com.brucecli.memory.retrieval.ScoredMemory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆。
 *
 * <p>长期记忆用于跨会话保存关键事实和用户偏好。
 * 默认路径是 ~/.bruce/memory/long_term_memory.json，
 * 同时支持 JVM 参数 -Dbruce.memory.dir=/path 和环境变量 BRUCE_MEMORY_DIR。</p>
 */
public class LongTermMemory {
    public static final String JVM_MEMORY_DIR = "bruce.memory.dir";
    public static final String ENV_MEMORY_DIR = "BRUCE_MEMORY_DIR";
    private static final String FILE_NAME = "long_term_memory.json";

    private final Path memoryFile;
    private final LinkedHashMap<String, MemoryEntry> entries = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final MemoryRetriever retriever = new MemoryRetriever();

    public LongTermMemory() throws IOException {
        this(resolveMemoryDir());
    }

    public LongTermMemory(Path memoryDir) throws IOException {
        Files.createDirectories(memoryDir);
        this.memoryFile = memoryDir.resolve(FILE_NAME);
        loadFromDisk();
    }

    public synchronized void store(MemoryEntry entry) throws IOException {
        entries.put(entry.id(), entry);
        saveToDisk();
    }

    public synchronized List<MemoryEntry> search(String query, int limit) {
        return retriever.retrieve(entries.values(), query, limit)
            .stream()
            .map(ScoredMemory::entry)
            .toList();
    }

    public synchronized List<MemoryEntry> entries() {
        return List.copyOf(entries.values());
    }

    public static Path resolveMemoryDir() {
        String jvmValue = System.getProperty(JVM_MEMORY_DIR);
        if (jvmValue != null && !jvmValue.isBlank()) {
            return Path.of(jvmValue).toAbsolutePath().normalize();
        }

        String envValue = System.getenv(ENV_MEMORY_DIR);
        if (envValue != null && !envValue.isBlank()) {
            return Path.of(envValue).toAbsolutePath().normalize();
        }

        return Path.of(System.getProperty("user.home"), ".bruce", "memory")
            .toAbsolutePath()
            .normalize();
    }

    private void loadFromDisk() throws IOException {
        if (!Files.exists(memoryFile)) {
            return;
        }
        String json = Files.readString(memoryFile, StandardCharsets.UTF_8);
        if (json.isBlank()) {
            return;
        }

        JsonNode root = mapper.readTree(json);
        JsonNode array = root.isArray() ? root : root.path("entries");
        if (!array.isArray()) {
            return;
        }

        for (JsonNode node : array) {
            MemoryEntry entry = fromJson(node);
            entries.put(entry.id(), entry);
        }
    }

    private void saveToDisk() throws IOException {
        ArrayNode array = mapper.createArrayNode();
        for (MemoryEntry entry : entries.values()) {
            array.add(toJson(entry));
        }

        Path tempFile = memoryFile.resolveSibling(memoryFile.getFileName() + ".tmp");
        Files.writeString(tempFile, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(array), StandardCharsets.UTF_8);
        Files.move(tempFile, memoryFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private ObjectNode toJson(MemoryEntry entry) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", entry.id());
        node.put("content", entry.content());
        node.put("type", entry.type().name());
        node.put("timestamp", entry.timestamp().toString());
        node.put("tokenCount", entry.tokenCount());

        ObjectNode metadata = mapper.createObjectNode();
        for (Map.Entry<String, String> item : entry.metadata().entrySet()) {
            metadata.put(item.getKey(), item.getValue());
        }
        node.set("metadata", metadata);
        return node;
    }

    private MemoryEntry fromJson(JsonNode node) {
        Map<String, String> metadata = new LinkedHashMap<>();
        JsonNode metadataNode = node.path("metadata");
        if (metadataNode.isObject()) {
            metadataNode.fields().forEachRemaining(field -> metadata.put(field.getKey(), field.getValue().asText()));
        }

        return new MemoryEntry(
            node.path("id").asText(),
            node.path("content").asText(),
            MemoryType.valueOf(node.path("type").asText(MemoryType.FACT.name())),
            Instant.parse(node.path("timestamp").asText()),
            metadata,
            node.path("tokenCount").asInt(TokenEstimator.estimateTokens(node.path("content").asText()))
        );
    }
}
