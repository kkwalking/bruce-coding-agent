package com.brucecli.memory.retrieval;

import com.brucecli.memory.model.MemoryEntry;

/**
 * 带分数的检索结果。
 *
 * <p>分数越高，说明这条记忆和当前用户输入越相关。</p>
 */
public record ScoredMemory(MemoryEntry entry, double score) {
}
