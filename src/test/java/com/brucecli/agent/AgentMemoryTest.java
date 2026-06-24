package com.brucecli.agent;

import com.brucecli.llm.ChatClient;
import com.brucecli.llm.ChatResponse;
import com.brucecli.llm.FunctionCall;
import com.brucecli.llm.Message;
import com.brucecli.llm.ToolCall;
import com.brucecli.llm.ToolDefinition;
import com.brucecli.memory.core.ConversationMemory;
import com.brucecli.memory.core.LongTermMemory;
import com.brucecli.memory.core.MemoryManager;
import com.brucecli.memory.model.MemoryEntry;
import com.brucecli.memory.tool.MemoryToolRegistrar;
import com.brucecli.skill.SkillToolRegistrar;
import com.brucecli.tool.Tool;
import com.brucecli.tool.ToolCallExecutor;
import com.brucecli.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentMemoryTest {
    @TempDir
    Path tempDir;

    @Test
    void injectsRelevantLongTermMemoryIntoLlmMessages() throws Exception {
        RecordingChatClient chatClient = new RecordingChatClient(new ChatResponse("已按记忆处理。", List.of()));
        MemoryManager memoryManager = memoryManager(200);
        memoryManager.saveFact("用户喜欢 JDK 17，生成 Java 代码时不要使用 JDK 8。");

        ToolRegistry toolRegistry = new ToolRegistry(tempDir);
        MemoryToolRegistrar.register(toolRegistry, memoryManager);
        Agent agent = new Agent(
            chatClient,
            toolRegistry,
            memoryManager,
            MemoryToolRegistrar.AGENT_INSTRUCTIONS,
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
        RecordingChatClient chatClient = new RecordingChatClient(
            new ChatResponse("", List.of(new ToolCall(
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
            ))),
            new ChatResponse("已保存长期偏好。", List.of())
        );
        MemoryManager memoryManager = memoryManager(300);
        ToolRegistry toolRegistry = new ToolRegistry(tempDir);
        MemoryToolRegistrar.register(toolRegistry, memoryManager);
        Agent agent = new Agent(
            chatClient,
            toolRegistry,
            memoryManager,
            MemoryToolRegistrar.AGENT_INSTRUCTIONS,
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

    @Test
    void skillToolResultsAreRedactedFromConversationAndMemory() throws Exception {
        RecordingChatClient chatClient = new RecordingChatClient(
            new ChatResponse("", List.of(new ToolCall(
                "load_secret_skill",
                new FunctionCall(SkillToolRegistrar.LOAD_TOOL_NAME, "{\"name\":\"secret\"}")
            ))),
            new ChatResponse("已加载。", List.of()),
            new ChatResponse("下一轮。", List.of())
        );
        MemoryManager memoryManager = memoryManager(1_000);
        ToolRegistry toolRegistry = new ToolRegistry(tempDir);
        registerSecretSkillTool(toolRegistry);
        Agent agent = new Agent(
            chatClient,
            toolRegistry,
            memoryManager,
            "",
            ToolCallExecutor.serial(toolRegistry)
        );

        assertEquals("已加载。", agent.run("加载 secret skill"));
        assertEquals("下一轮。", agent.run("继续普通任务"));

        List<Message> secondTurnMessages = chatClient.calls.get(2);
        assertFalse(secondTurnMessages.stream().anyMatch(message ->
            message.content() != null && message.content().contains("SECRET_SKILL_BODY")
        ));
        assertTrue(secondTurnMessages.stream().anyMatch(message ->
            "tool".equals(message.role()) && message.content().contains("已从历史中移除")
        ));
        assertTrue(secondTurnMessages.stream().anyMatch(message ->
            "system".equals(message.role()) && message.content().contains("未写入 Memory")
        ));
    }

    private MemoryManager memoryManager(int maxTokens) throws IOException {
        return new MemoryManager(
            new ConversationMemory(maxTokens),
            new LongTermMemory(tempDir.resolve("memory")),
            entries -> MemoryEntry.summary("测试摘要", Map.of("source_count", String.valueOf(entries.size())))
        );
    }

    private void registerSecretSkillTool(ToolRegistry toolRegistry) {
        toolRegistry.register(new Tool(
            SkillToolRegistrar.LOAD_TOOL_NAME,
            "测试用 Skill 工具",
            new ObjectMapper().createObjectNode().put("type", "object"),
            args -> "SECRET_SKILL_BODY"
        ));
    }

    private static class RecordingChatClient implements ChatClient {
        private final Queue<ChatResponse> responses = new ArrayDeque<>();
        private final List<List<Message>> calls = new ArrayList<>();
        private List<Message> lastMessages = List.of();
        private List<ToolDefinition> lastTools = List.of();

        RecordingChatClient(ChatResponse... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<ToolDefinition> tools) throws IOException {
            this.lastMessages = List.copyOf(messages);
            this.lastTools = List.copyOf(tools);
            this.calls.add(List.copyOf(messages));
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("no queued response");
            }
            return response;
        }
    }
}
