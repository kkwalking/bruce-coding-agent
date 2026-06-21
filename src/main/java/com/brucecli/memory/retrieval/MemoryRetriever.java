package com.brucecli.memory.retrieval;

import com.brucecli.memory.model.MemoryEntry;
import com.brucecli.memory.model.MemoryType;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 记忆检索器。
 *
 * <p>文章评论里提到实际项目中长期记忆和 MemoryRetriever 都会做检索。
 * 本模块按原文思路引入 jieba-analysis，默认使用 {@link JiebaWordSegmenter} 做中文分词。</p>
 */
public class MemoryRetriever {
    private final JiebaWordSegmenter wordSegmenter = new JiebaWordSegmenter();

    public MemoryRetriever() {
    }

    public List<ScoredMemory> retrieve(Collection<MemoryEntry> entries, String query, int limit) {
        if (entries == null || entries.isEmpty() || query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }

        Set<String> queryTerms = wordSegmenter.segment(query);
        Instant now = Instant.now();
        return entries.stream()
            .map(entry -> new ScoredMemory(entry, score(entry, queryTerms, now)))
            .filter(scored -> scored.score() > 0)
            .sorted((left, right) -> Double.compare(right.score(), left.score()))
            .limit(limit)
            .toList();
    }

    private double score(MemoryEntry entry, Set<String> queryTerms, Instant now) {
        Set<String> memoryTerms = wordSegmenter.segment(entry.content());
        double overlap = 0;
        for (String term : queryTerms) {
            if (memoryTerms.contains(term)) {
                overlap += term.length() > 1 ? 2.0 : 1.0;
            }
        }

        // FACT 通常比普通对话更值得注入；SUMMARY 次之。
        double typeBoost = switch (entry.type()) {
            case FACT -> 2.0;
            case SUMMARY -> 1.4;
            case TOOL_RESULT -> 0.9;
            case CONVERSATION -> 1.0;
        };

        long ageHours = Math.max(0, Duration.between(entry.timestamp(), now).toHours());
        double recencyBoost = 1.0 / (1.0 + ageHours / 72.0);
        return overlap * typeBoost * recencyBoost;
    }
}
