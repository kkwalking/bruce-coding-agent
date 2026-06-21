package com.brucecli.agent.memory;

import com.brucecli.llm.ChatClient;
import com.brucecli.llm.ChatResponse;
import com.brucecli.llm.FunctionCall;
import com.brucecli.llm.Message;
import com.brucecli.llm.ToolDefinition;
import com.brucecli.llm.ToolCall;
import com.brucecli.tool.ToolCallExecutor;
import com.brucecli.memory.core.ConversationMemory;
import com.brucecli.memory.core.LongTermMemory;
import com.brucecli.memory.core.MemoryManager;
import com.brucecli.memory.model.MemoryEntry;
import com.brucecli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryAwareAgentTest {
    @TempDir
    Path tempDir;

    @Test
    void injectsRelevantLongTermMemoryIntoLlmMessages() throws Exception {
        RecordingChatClient chatClient = new RecordingChatClient();
        MemoryManager memoryManager = new MemoryManager(
            new ConversationMemory(200),
            new LongTermMemory(tempDir),
            entries -> MemoryEntry.summary("测试摘要", Map.of())
        );
        memoryManager.saveFact("用户喜欢 JDK 17，生成 Java 代码时不要使用 JDK 8。");

        ToolRegistry toolRegistry = new ToolRegistry(tempDir);
        MemoryAwareAgent agent = new MemoryAwareAgent(
            chatClient,
            toolRegistry,
            memoryManager,
            "",
            ToolCallExecutor.serial(toolRegistry)
        );

        String answer = agent.run("请用 JDK 17 创建一个 demo");

        assertEquals("已按记忆处理。", answer);
        assertTrue(chatClient.lastMessages.stream().anyMatch(message ->
            message.role().equals("system") && message.content().contains("用户喜欢 JDK 17")
        ));
        assertTrue(chatClient.lastTools.stream().anyMatch(tool -> tool.name().equals("save_long_term_memory")));
    }

    @Test
    void modelCanCallSaveLongTermMemoryTool() throws Exception {
        ScriptedMemoryToolChatClient chatClient = new ScriptedMemoryToolChatClient();
        MemoryManager memoryManager = new MemoryManager(
            new ConversationMemory(300),
            new LongTermMemory(tempDir),
            entries -> MemoryEntry.summary("测试摘要", Map.of())
        );
        ToolRegistry toolRegistry = new ToolRegistry(tempDir);
        MemoryAwareAgent agent = new MemoryAwareAgent(
            chatClient,
            toolRegistry,
            memoryManager,
            "",
            ToolCallExecutor.serial(toolRegistry)
        );

        String answer = agent.run("以后 Java 项目都使用 JDK 17 和 Maven。");
        List<MemoryEntry> longTermEntries = memoryManager.searchLongTerm("JDK 17 Maven", 5);

        assertEquals("已保存长期偏好。", answer);
        assertEquals(1, longTermEntries.size());
        assertEquals("用户偏好：Java 项目默认使用 JDK 17 和 Maven。", longTermEntries.get(0).content());
        assertEquals("tool:save_long_term_memory", longTermEntries.get(0).metadata().get("source"));
        assertEquals("用户明确表达以后默认使用该技术栈", longTermEntries.get(0).metadata().get("reason"));
    }

    private static class RecordingChatClient implements ChatClient {
        private List<Message> lastMessages = List.of();
        private List<ToolDefinition> lastTools = List.of();

        @Override
        public ChatResponse chat(List<Message> messages, List<ToolDefinition> tools) throws IOException {
            this.lastMessages = List.copyOf(messages);
            this.lastTools = List.copyOf(tools);
            return new ChatResponse("已按记忆处理。", List.of());
        }
    }

    private static class ScriptedMemoryToolChatClient implements ChatClient {
        private int callCount;

        @Override
        public ChatResponse chat(List<Message> messages, List<ToolDefinition> tools) {
            callCount++;
            if (callCount == 1) {
                return new ChatResponse("", List.of(new ToolCall(
                    "call_save_memory_1",
                    new FunctionCall(
                        "save_long_term_memory",
                        """
                            {
                              "content": "用户偏好：Java 项目默认使用 JDK 17 和 Maven。",
                              "reason": "用户明确表达以后默认使用该技术栈"
                            }
                            """
                    )
                )));
            }
            return new ChatResponse("已保存长期偏好。", List.of());
        }
    }
}
