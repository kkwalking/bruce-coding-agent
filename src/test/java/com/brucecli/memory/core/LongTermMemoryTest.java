package com.brucecli.memory.core;

import com.brucecli.memory.model.MemoryEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongTermMemoryTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsFactsToDiskAndLoadsThemAgain() throws Exception {
        LongTermMemory memory = new LongTermMemory(tempDir);
        memory.store(MemoryEntry.fact("用户喜欢使用 JDK 17 和 Maven"));

        assertTrue(Files.exists(tempDir.resolve("long_term_memory.json")));

        LongTermMemory reloaded = new LongTermMemory(tempDir);

        assertEquals(1, reloaded.entries().size());
        assertFalse(reloaded.search("JDK 17", 5).isEmpty());
    }
}
