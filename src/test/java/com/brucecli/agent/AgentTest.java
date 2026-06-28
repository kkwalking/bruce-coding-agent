package com.brucecli.agent;

import com.brucecli.event.BruceEvent;
import com.brucecli.event.BruceEvents;
import com.brucecli.llm.ChatClient;
import com.brucecli.llm.ChatResponse;
import com.brucecli.llm.ContentPart;
import com.brucecli.llm.FunctionCall;
import com.brucecli.llm.Message;
import com.brucecli.llm.PreparedUserInput;
import com.brucecli.llm.ToolCall;
import com.brucecli.llm.ToolDefinition;
import com.brucecli.memory.core.ConversationMemory;
import com.brucecli.memory.core.LongTermMemory;
import com.brucecli.memory.core.MemoryManager;
import com.brucecli.memory.model.MemoryEntry;
import com.brucecli.tool.ToolCallExecutor;
import com.brucecli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTest {
    @TempDir
    Path tempDir;

    @Test
    void runsToolCallThenReturnsFinalAnswer() throws Exception {
        // 用队列模拟两轮大模型响应：
        // 第一轮要求调用 write_file，第二轮在工具结果写入历史后给出最终回答。
        Queue<ChatResponse> responses = new ArrayDeque<>();
        responses.add(new ChatResponse(
            "",
            List.of(new ToolCall(
                "call-1",
                new FunctionCall("write_file", "{\"path\":\"demo.txt\",\"content\":\"Hello BruceCLI\"}")
            ))
        ));
        responses.add(new ChatResponse("文件已经写好。", List.of()));

        ToolRegistry toolRegistry = new ToolRegistry(tempDir);
        Agent agent = new Agent(
            new QueueChatClient(responses),
            toolRegistry,
            memoryManager(),
            "",
            ToolCallExecutor.serial(toolRegistry)
        );

        String answer = agent.run("写一个 demo.txt");

        assertEquals("文件已经写好。", answer);
        assertEquals("Hello BruceCLI", Files.readString(tempDir.resolve("demo.txt")));
    }

    @Test
    void emitsStreamingAssistantEventsForPlainReactTurn() throws Exception {
        List<BruceEvent> events = new ArrayList<>();
        Agent agent = new Agent(
            new StreamingChatClient(new ChatResponse("你好 Bruce", List.of()), List.of("你好 ", "Bruce")),
            ToolRegistry.empty(tempDir),
            memoryManager(),
            "",
            toolCalls -> List.of(),
            events::add
        );

        String answer = agent.run("你好", "", "run-plain");

        assertEquals("你好 Bruce", answer);
        assertEquals(List.of(
            "message:user",
            "message_started:assistant",
            "delta:content:你好 ",
            "delta:content:Bruce",
            "message:assistant"
        ), eventSequence(events));
        assertTrue(events.stream()
            .filter(BruceEvents.MessageCompleted.class::isInstance)
            .map(BruceEvents.MessageCompleted.class::cast)
            .map(BruceEvents.MessageCompleted::message)
            .noneMatch(message -> "system".equals(message.role())));
    }

    @Test
    void emitsToolLifecycleEventsInProtocolOrder() throws Exception {
        List<BruceEvent> events = new ArrayList<>();
        Queue<ChatResponse> responses = new ArrayDeque<>();
        responses.add(new ChatResponse(
            "",
            List.of(new ToolCall(
                "call-1",
                new FunctionCall("write_file", "{\"path\":\"demo.txt\",\"content\":\"Hello\"}")
            ))
        ));
        responses.add(new ChatResponse("文件已经写好。", List.of()));
        ToolRegistry toolRegistry = new ToolRegistry(tempDir);
        Agent agent = new Agent(
            new QueueChatClient(responses),
            toolRegistry,
            memoryManager(),
            "",
            ToolCallExecutor.serial(toolRegistry),
            events::add
        );

        assertEquals("文件已经写好。", agent.run("写文件", "", "run-tool"));

        assertEquals(List.of(
            "message:user",
            "message_started:assistant",
            "message:assistant",
            "tool_started:write_file",
            "tool_completed:write_file",
            "message:tool",
            "message_started:assistant",
            "delta:content:文件已经写好。",
            "message:assistant"
        ), eventSequence(events));
    }

    @Test
    void prunesHistoricalImageContentBeforeNextModelCall() throws Exception {
        RecordingChatClient chatClient = new RecordingChatClient(
            new ChatResponse("第一张已处理。", List.of()),
            new ChatResponse("第二张已处理。", List.of())
        );
        Agent agent = new Agent(
            chatClient,
            ToolRegistry.empty(tempDir),
            memoryManager(),
            "",
            toolCalls -> List.of()
        );

        agent.run(PreparedUserInput.multimodal(List.of(
            ContentPart.text("第一张"),
            ContentPart.imageUrl("data:image/png;base64,OLD", "[已附加图片: old.png]")
        )), "");
        agent.run(PreparedUserInput.multimodal(List.of(
            ContentPart.text("第二张"),
            ContentPart.imageUrl("data:image/png;base64,NEW", "[已附加图片: new.png]")
        )), "");

        List<Message> secondCallMessages = chatClient.calls.get(1);
        long imageMessageCount = secondCallMessages.stream()
            .filter(Message::hasImageContent)
            .count();
        assertEquals(1, imageMessageCount);
        assertTrue(secondCallMessages.stream().anyMatch(message ->
            message.content() != null && message.content().contains("历史图片内容已移除")
        ));
        assertTrue(secondCallMessages.stream().anyMatch(message ->
            message.hasImageContent() && message.content().contains("第二张")
        ));
    }

    private MemoryManager memoryManager() throws IOException {
        return new MemoryManager(
            new ConversationMemory(1_000),
            new LongTermMemory(tempDir.resolve("memory")),
            entries -> MemoryEntry.summary("测试摘要", Map.of("source_count", String.valueOf(entries.size())))
        );
    }

    private static List<String> eventSequence(List<BruceEvent> events) {
        return events.stream()
            .map(AgentTest::eventLabel)
            .toList();
    }

    private static String eventLabel(BruceEvent event) {
        if (event instanceof BruceEvents.MessageCompleted completed) {
            return "message:" + completed.message().role();
        }
        if (event instanceof BruceEvents.MessageStarted started) {
            return "message_started:" + started.role();
        }
        if (event instanceof BruceEvents.MessageDelta delta) {
            return "delta:" + delta.channel() + ":" + delta.delta();
        }
        if (event instanceof BruceEvents.ToolCallStarted started) {
            return "tool_started:" + started.toolCall().function().name();
        }
        if (event instanceof BruceEvents.ToolCallCompleted completed) {
            return "tool_completed:" + completed.toolCall().function().name();
        }
        return event.type();
    }

    /**
     * 测试用的假模型客户端。
     *
     * <p>学习重点：Agent 不需要知道响应来自真实 DeepSeek 还是测试队列，
     * 只要实现 ChatClient 接口即可。</p>
     */
    private static class QueueChatClient implements ChatClient {
        private final Queue<ChatResponse> responses;

        QueueChatClient(Queue<ChatResponse> responses) {
            this.responses = responses;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<ToolDefinition> tools) throws IOException {
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("no queued response");
            }
            return response;
        }
    }

    private static class RecordingChatClient implements ChatClient {
        private final Queue<ChatResponse> responses = new ArrayDeque<>();
        private final List<List<Message>> calls = new ArrayList<>();

        RecordingChatClient(ChatResponse... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<ToolDefinition> tools) throws IOException {
            calls.add(List.copyOf(messages));
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("no queued response");
            }
            return response;
        }
    }

    private static class StreamingChatClient implements ChatClient {
        private final ChatResponse response;
        private final List<String> contentDeltas;

        StreamingChatClient(ChatResponse response, List<String> contentDeltas) {
            this.response = response;
            this.contentDeltas = contentDeltas;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<ToolDefinition> tools) {
            return response;
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<ToolDefinition> tools, StreamListener listener) {
            for (String delta : contentDeltas) {
                listener.onContentDelta(delta);
            }
            return response;
        }
    }
}
