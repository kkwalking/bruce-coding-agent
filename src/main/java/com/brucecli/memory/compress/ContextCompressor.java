package com.brucecli.memory.compress;

import com.brucecli.memory.model.MemoryEntry;

import java.io.IOException;
import java.util.List;

/**
 * 上下文压缩器。
 *
 * <p>当短期记忆超出预算，或者达到压缩阈值时，
 * MemoryManager 会把一批旧消息交给压缩器，生成 SUMMARY 记忆。</p>
 */
public interface ContextCompressor {
    MemoryEntry compress(List<MemoryEntry> entries) throws IOException;
}
