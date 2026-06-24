package com.brucecli.agent;

import com.brucecli.llm.ChatClient;
import com.brucecli.llm.ChatResponse;
import com.brucecli.llm.ContentPart;
import com.brucecli.llm.FunctionCall;
import com.brucecli.llm.Message;
import com.brucecli.llm.PreparedUserInput;
import com.brucecli.llm.ToolCall;
import com.brucecli.llm.ToolDefinition;
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
            "",
            ToolCallExecutor.serial(toolRegistry)
        );

        String answer = agent.run("写一个 demo.txt");

        assertEquals("文件已经写好。", answer);
        assertEquals("Hello BruceCLI", Files.readString(tempDir.resolve("demo.txt")));
    }

    @Test
    void prunesHistoricalImageContentBeforeNextModelCall() {
        RecordingChatClient chatClient = new RecordingChatClient(
            new ChatResponse("第一张已处理。", List.of()),
            new ChatResponse("第二张已处理。", List.of())
        );
        Agent agent = new Agent(
            chatClient,
            ToolRegistry.empty(tempDir),
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
}
