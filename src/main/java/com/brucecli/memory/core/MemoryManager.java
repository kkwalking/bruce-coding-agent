package com.brucecli.memory.core;

import com.brucecli.memory.compress.ContextCompressor;
import com.brucecli.memory.model.MemoryEntry;
import com.brucecli.memory.model.MemoryType;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Memory 系统门面。
 *
 * <p>文章里说 Agent 对外只需要两个操作：存消息、取记忆。
 * 这个类就是这层门面：内部组合 ConversationMemory、LongTermMemory、
 * ContextCompressor、TokenBudget 和 MemoryRetriever 思想。</p>
 */
public class MemoryManager {
    private static final int DEFAULT_RELEVANT_LIMIT = 5;
    private static final int DEFAULT_RECENT_LIMIT = 8;
    private static final int DEFAULT_KEEP_RECENT_AFTER_COMPRESSION =
        ConversationMemory.DEFAULT_KEEP_RECENT_AFTER_COMPRESSION;
    private static final double DEFAULT_COMPRESSION_THRESHOLD =
        ConversationMemory.DEFAULT_COMPRESSION_THRESHOLD;

    private final ConversationMemory conversationMemory;
    private final LongTermMemory longTermMemory;
    private final ContextCompressor compressor;
    private final int relevantLimit;
    private final int recentLimit;
    private int contextBuildCount;
    private int lastContextTokens;
    private int lastRelevantLongTermEntries;
    private int lastSummaryEntries;
    private int lastRecentShortTermEntries;
    private int compressionCount;
    private int compressedSourceEntries;

    public MemoryManager(
        ConversationMemory conversationMemory,
        LongTermMemory longTermMemory,
        ContextCompressor compressor
    ) {
        this(
            conversationMemory,
            longTermMemory,
            compressor,
            DEFAULT_RELEVANT_LIMIT,
            DEFAULT_RECENT_LIMIT,
            DEFAULT_KEEP_RECENT_AFTER_COMPRESSION,
            DEFAULT_COMPRESSION_THRESHOLD
        );
    }

    public MemoryManager(
        ConversationMemory conversationMemory,
        LongTermMemory longTermMemory,
        ContextCompressor compressor,
        int relevantLimit,
        int recentLimit,
        int keepRecentAfterCompression,
        double compressionThreshold
    ) {
        this.conversationMemory = conversationMemory;
        this.longTermMemory = longTermMemory;
        this.compressor = compressor;
        this.relevantLimit = relevantLimit;
        this.recentLimit = recentLimit;
        this.conversationMemory.configureCompression(keepRecentAfterCompression, compressionThreshold);
    }

    public void rememberUserMessage(String content) {
        conversationMemory.store(MemoryEntry.conversation("user", content));
    }

    public void rememberAssistantMessage(String content) {
        conversationMemory.store(MemoryEntry.conversation("assistant", content));
    }

    public void rememberToolResult(String toolName, String content) {
        conversationMemory.store(MemoryEntry.toolResult(toolName, content));
    }

    /**
     * 手动保存长期事实，对应 CLI 的 /save 命令。
     */
    public void saveFact(String content) throws IOException {
        saveFact(content, Map.of());
    }

    /**
     * 带 metadata 保存长期事实。
     *
     * <p>metadata 用来记录这条记忆的来源、保存原因等附加信息。
     * 真实 Memory 系统里这些字段后续可以参与过滤、审计或排序。</p>
     */
    public void saveFact(String content, Map<String, String> metadata) throws IOException {
        if (metadata == null || metadata.isEmpty()) {
            longTermMemory.store(MemoryEntry.fact(content));
            return;
        }
        longTermMemory.store(MemoryEntry.of(content, MemoryType.FACT, metadata));
    }

    /**
     * 给工具调用使用的长期事实保存入口。
     *
     * @param source 记忆来源，例如 tool:save_long_term_memory
     * @param reason 模型给出的保存原因，方便学习和排查“为什么这条被记住了”
     */
    public void saveFact(String content, String source, String reason) throws IOException {
        Map<String, String> metadata = new java.util.LinkedHashMap<>();
        if (source != null && !source.isBlank()) {
            metadata.put("source", source);
        }
        if (reason != null && !reason.isBlank()) {
            metadata.put("reason", reason);
        }
        if (metadata.isEmpty()) {
            saveFact(content);
            return;
        }
        saveFact(content, metadata);
    }

    public List<MemoryEntry> searchLongTerm(String query, int limit) {
        return longTermMemory.search(query, limit);
    }

    public void clearShortTerm() {
        conversationMemory.clear();
    }

    /**
     * 生成本轮要注入给 LLM 的记忆上下文。
     *
     * <p>真实 Agent 不应该把所有历史消息原样塞进 List&lt;Message&gt;。
     * 这里会先触发必要的压缩，然后只选择相关长期记忆、摘要和最近短期消息。</p>
     */
    public MemoryContext buildContext(String currentInput) throws IOException {
        compressIfNeeded();

        List<MemoryEntry> relevantLongTerm = longTermMemory.search(currentInput, relevantLimit);
        List<MemoryEntry> summaries = conversationMemory.compressedSummaries();
        List<MemoryEntry> recent = conversationMemory.recentEntries(recentLimit);
        String prompt = renderPrompt(relevantLongTerm, summaries, recent);
        recordContextStats(prompt, relevantLongTerm, summaries, recent);

        return new MemoryContext(relevantLongTerm, summaries, recent, prompt);
    }

    private boolean shouldCompress() {
        return conversationMemory.hasPendingCompression();
    }

    public void compressIfNeeded() throws IOException {
        List<MemoryEntry> candidates = conversationMemory.drainPendingCompression();
        if (candidates.isEmpty()) {
            return;
        }

        MemoryEntry summary = compressor.compress(candidates);
        conversationMemory.addCompressedSummary(summary);
        compressionCount++;
        compressedSourceEntries += candidates.size();
    }

    public MemoryStatus status() {
        return new MemoryStatus(
            conversationMemory.entries().size(),
            conversationMemory.currentTokens(),
            conversationMemory.maxTokens(),
            conversationMemory.getUsageRatio(),
            conversationMemory.pendingCompressionCount(),
            conversationMemory.compressedSummaryCount(),
            longTermMemory.entries().size(),
            contextBuildCount,
            lastContextTokens,
            lastRelevantLongTermEntries,
            lastSummaryEntries,
            lastRecentShortTermEntries,
            compressionCount,
            compressedSourceEntries,
            shouldCompress()
        );
    }

    private void recordContextStats(
        String prompt,
        List<MemoryEntry> relevantLongTerm,
        List<MemoryEntry> summaries,
        List<MemoryEntry> recent
    ) {
        contextBuildCount++;
        lastContextTokens = TokenEstimator.estimateTokens(prompt);
        lastRelevantLongTermEntries = relevantLongTerm.size();
        lastSummaryEntries = summaries.size();
        lastRecentShortTermEntries = recent.size();
    }

    private String renderPrompt(
        List<MemoryEntry> relevantLongTerm,
        List<MemoryEntry> summaries,
        List<MemoryEntry> recent
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("以下是 Memory 系统检索到的上下文。它们可能有用，但如果和当前任务无关，可以忽略。\n");

        appendSection(builder, "相关长期记忆", relevantLongTerm);
        appendSection(builder, "压缩摘要", summaries);
        appendSection(builder, "最近短期记忆", recent);
        return builder.toString();
    }

    private void appendSection(StringBuilder builder, String title, List<MemoryEntry> entries) {
        builder.append("\n## ").append(title).append('\n');
        if (entries.isEmpty()) {
            builder.append("- 无\n");
            return;
        }
        builder.append(entries.stream()
            .map(entry -> "- " + entry.toPromptLine())
            .collect(Collectors.joining("\n")));
        builder.append('\n');
    }
}
