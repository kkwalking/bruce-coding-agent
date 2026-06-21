package com.brucecli.memory.core;

import com.brucecli.memory.model.MemoryEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 短期记忆。
 *
 * <p>短期记忆跟着当前进程和当前会话走，负责保存最近的对话上下文。
 * 它的关键约束是 maxTokens：一旦超过预算，就按 FIFO 淘汰最旧条目。
 * 被淘汰的内容不会直接丢掉，而是进入 pendingCompression，等待 MemoryManager 压缩成摘要。</p>
 */
public class ConversationMemory {
    private final LinkedHashMap<String, MemoryEntry> entries = new LinkedHashMap<>();
    private final int maxTokens;
    private final AtomicInteger currentTokens = new AtomicInteger();
    private final List<MemoryEntry> pendingCompression = new ArrayList<>();
    private final List<MemoryEntry> compressedSummaries = new ArrayList<>();

    public ConversationMemory(int maxTokens) {
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens 必须大于 0");
        }
        this.maxTokens = maxTokens;
    }

    public synchronized void store(MemoryEntry entry) {
        MemoryEntry old = entries.put(entry.id(), entry);
        if (old != null) {
            currentTokens.addAndGet(-old.tokenCount());
        }
        currentTokens.addAndGet(entry.tokenCount());

        // 超出预算时自动淘汰最旧的条目。
        while (currentTokens.get() > maxTokens && entries.size() > 1) {
            evictOldest();
        }
    }

    public synchronized List<MemoryEntry> entries() {
        return List.copyOf(entries.values());
    }

    public synchronized void clear() {
        entries.clear();
        pendingCompression.clear();
        compressedSummaries.clear();
        currentTokens.set(0);
    }

    public synchronized int currentTokens() {
        return currentTokens.get();
    }

    public int maxTokens() {
        return maxTokens;
    }

    public synchronized double getUsageRatio() {
        return currentTokens.get() * 1.0 / maxTokens;
    }

    public synchronized List<MemoryEntry> recentEntries(int limit) {
        List<MemoryEntry> all = new ArrayList<>(entries.values());
        int fromIndex = Math.max(0, all.size() - Math.max(0, limit));
        return List.copyOf(all.subList(fromIndex, all.size()));
    }

    public synchronized List<MemoryEntry> compressedSummaries() {
        return List.copyOf(compressedSummaries);
    }

    public synchronized int compressedSummaryCount() {
        return compressedSummaries.size();
    }

    public synchronized boolean hasPendingCompression() {
        return !pendingCompression.isEmpty();
    }

    public synchronized int pendingCompressionCount() {
        return pendingCompression.size();
    }

    /**
     * 取出等待压缩的旧消息。
     *
     * <p>drain 语义表示这些旧消息已经交给压缩器处理，避免下次重复压缩。</p>
     */
    public synchronized List<MemoryEntry> drainPendingCompression() {
        List<MemoryEntry> drained = List.copyOf(pendingCompression);
        pendingCompression.clear();
        return drained;
    }

    public synchronized void addCompressedSummary(MemoryEntry summary) {
        compressedSummaries.add(summary);
        store(summary);
    }

    /**
     * 主动压缩时使用：保留最近 keepCount 条，移出更早的历史。
     */
    public synchronized List<MemoryEntry> removeOldestButKeep(int keepCount) {
        List<MemoryEntry> removed = new ArrayList<>();
        while (entries.size() > keepCount) {
            removed.add(evictOldest());
        }
        return removed;
    }

    private MemoryEntry evictOldest() {
        Map.Entry<String, MemoryEntry> eldest = entries.entrySet().iterator().next();
        MemoryEntry removed = entries.remove(eldest.getKey());
        currentTokens.addAndGet(-removed.tokenCount());
        pendingCompression.add(removed);
        return removed;
    }
}
