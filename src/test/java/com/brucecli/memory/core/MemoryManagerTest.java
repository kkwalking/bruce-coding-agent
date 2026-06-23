package com.brucecli.memory.core;

import com.brucecli.memory.model.MemoryEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void buildsContextFromLongTermRecentAndCompressedMemory() throws Exception {
        MemoryManager manager = new MemoryManager(
            new ConversationMemory(30),
            new LongTermMemory(tempDir),
            entries -> MemoryEntry.summary("测试摘要", Map.of("source_count", String.valueOf(entries.size())))
        );

        manager.saveFact("用户喜欢 JDK 17，项目默认使用 Maven 构建。");
        manager.rememberUserMessage("第一轮：请创建一个 Java 项目，消息很长很长，用来触发淘汰。");
        manager.rememberAssistantMessage("第二轮：已经创建 pom.xml，消息继续变长，用来触发压缩。");
        manager.rememberUserMessage("第三轮：继续补充 README，还是很长很长。");
        manager.rememberAssistantMessage("第四轮：已经补充 README，继续让短期记忆达到压缩阈值。");

        MemoryContext context = manager.buildContext("JDK 17 Maven 项目");

        assertFalse(context.relevantLongTerm().isEmpty());
        assertFalse(context.summaries().isEmpty());
        assertTrue(context.prompt().contains("相关长期记忆"));
        assertTrue(context.prompt().contains("压缩摘要"));
    }

    @Test
    void statusReportsContextTokensAndCompressionCount() throws Exception {
        MemoryManager manager = new MemoryManager(
            new ConversationMemory(30),
            new LongTermMemory(tempDir),
            entries -> MemoryEntry.summary("测试摘要", Map.of("source_count", String.valueOf(entries.size())))
        );

        manager.rememberUserMessage("第一轮：这是一段很长的用户消息，用来触发短期记忆预算淘汰。");
        manager.rememberAssistantMessage("第二轮：这是一段很长的助手回复，用来进入待压缩队列。");
        manager.rememberUserMessage("第三轮：继续写一段很长的消息，让 buildContext 触发压缩。");
        manager.rememberAssistantMessage("第四轮：继续写一段很长的助手回复，确保超过最近保留条数。");

        manager.buildContext("查看状态");
        MemoryStatus status = manager.status();

        assertEquals(1, status.contextBuildCount());
        assertTrue(status.lastContextTokens() > 0);
        assertTrue(status.compressionCount() > 0);
        assertTrue(status.compressedSourceEntries() > 0);
        assertFalse(status.toMarkdown().isBlank());
    }

    @Test
    void buildContextDoesNotCompressSamePendingBatchTwice() throws Exception {
        List<Integer> compressedSourceCounts = new ArrayList<>();
        MemoryManager manager = new MemoryManager(
            new ConversationMemory(100),
            new LongTermMemory(tempDir),
            entries -> {
                compressedSourceCounts.add(entries.size());
                return MemoryEntry.summary("测试摘要", Map.of("source_count", String.valueOf(entries.size())));
            },
            5,
            8,
            2,
            0.80
        );

        manager.rememberUserMessage("a".repeat(120));
        manager.rememberAssistantMessage("b".repeat(120));
        manager.rememberUserMessage("c".repeat(120));

        manager.buildContext("第一次构建上下文");
        manager.buildContext("第二次构建上下文");

        assertEquals(List.of(1), compressedSourceCounts);
        assertEquals(1, manager.status().compressionCount());
        assertFalse(manager.status().shouldCompress());
    }

    @Test
    void customCompressionPolicyIsAppliedToConversationMemoryStores() throws Exception {
        MemoryManager manager = new MemoryManager(
            new ConversationMemory(100),
            new LongTermMemory(tempDir),
            entries -> MemoryEntry.summary("测试摘要", Map.of("source_count", String.valueOf(entries.size()))),
            5,
            8,
            1,
            0.50
        );

        manager.rememberUserMessage("a".repeat(120));
        manager.rememberAssistantMessage("b".repeat(120));

        MemoryStatus status = manager.status();

        assertEquals(1, status.pendingCompressionEntries());
        assertEquals(1, status.shortTermEntries());
        assertTrue(status.shouldCompress());
    }
}
