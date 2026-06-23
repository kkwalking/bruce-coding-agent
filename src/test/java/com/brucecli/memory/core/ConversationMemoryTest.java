package com.brucecli.memory.core;

import com.brucecli.memory.model.MemoryEntry;
import com.brucecli.memory.model.MemoryType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationMemoryTest {
    @Test
    void movesOldestEntriesIntoPendingCompressionWhenThresholdReached() {
        ConversationMemory memory = new ConversationMemory(100, 2, 0.80);

        memory.store(entry("first", 30));
        memory.store(entry("second", 30));
        memory.store(entry("third", 20));

        assertEquals(50, memory.currentTokens());
        assertEquals(2, memory.entries().size());
        assertTrue(memory.hasPendingCompression());
        assertEquals("first", memory.drainPendingCompression().get(0).id());
    }

    @Test
    void keepsRecentEntriesEvenWhenThresholdIsStillExceeded() {
        ConversationMemory memory = new ConversationMemory(100, 3, 0.80);

        memory.store(entry("first", 30));
        memory.store(entry("second", 30));
        memory.store(entry("third", 30));

        assertEquals(90, memory.currentTokens());
        assertEquals(3, memory.entries().size());
        assertFalse(memory.hasPendingCompression());
    }

    @Test
    void defaultConstructorUsesCompressionThresholdBeforeFullBudget() {
        ConversationMemory memory = new ConversationMemory(100);

        memory.store(entry("first", 20));
        memory.store(entry("second", 20));
        memory.store(entry("third", 20));
        memory.store(entry("fourth", 20));

        assertEquals(60, memory.currentTokens());
        assertEquals(3, memory.entries().size());
        assertTrue(memory.hasPendingCompression());
    }

    private static MemoryEntry entry(String id, int tokenCount) {
        return new MemoryEntry(
            id,
            "entry " + id,
            MemoryType.CONVERSATION,
            Instant.now(),
            Map.of("role", "user"),
            tokenCount
        );
    }
}
