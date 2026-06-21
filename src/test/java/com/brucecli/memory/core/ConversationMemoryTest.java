package com.brucecli.memory.core;

import com.brucecli.memory.model.MemoryEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationMemoryTest {
    @Test
    void evictsOldestEntriesIntoPendingCompressionWhenBudgetExceeded() {
        ConversationMemory memory = new ConversationMemory(20);

        memory.store(MemoryEntry.conversation("user", "第一条消息，内容比较长，需要占用不少 token"));
        memory.store(MemoryEntry.conversation("assistant", "第二条消息，也比较长，用来触发预算淘汰"));
        memory.store(MemoryEntry.conversation("user", "第三条消息继续撑大上下文"));

        assertTrue(memory.currentTokens() <= memory.maxTokens());
        assertTrue(memory.hasPendingCompression());
        assertFalse(memory.drainPendingCompression().isEmpty());
    }
}
