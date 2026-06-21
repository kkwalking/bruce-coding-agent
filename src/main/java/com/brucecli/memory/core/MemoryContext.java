package com.brucecli.memory.core;

import com.brucecli.memory.model.MemoryEntry;

import java.util.List;

/**
 * 每轮发送给 LLM 前组装出来的记忆上下文。
 *
 * <p>它不是直接等同于 List&lt;Message&gt;。
 * 这里会把长期相关事实、压缩摘要、最近短期消息整理成一个 system prompt 片段，
 * 避免无限制地把所有历史消息塞进模型上下文。</p>
 */
public record MemoryContext(
    List<MemoryEntry> relevantLongTerm,
    List<MemoryEntry> summaries,
    List<MemoryEntry> recentShortTerm,
    String prompt
) {
    public MemoryContext {
        relevantLongTerm = relevantLongTerm == null ? List.of() : List.copyOf(relevantLongTerm);
        summaries = summaries == null ? List.of() : List.copyOf(summaries);
        recentShortTerm = recentShortTerm == null ? List.of() : List.copyOf(recentShortTerm);
        prompt = prompt == null ? "" : prompt;
    }
}
