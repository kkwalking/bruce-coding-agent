package com.brucecli.integrated.runtime;

import com.brucecli.approval.ApprovalRequest;
import com.brucecli.approval.ApprovalResult;
import com.brucecli.approval.HitlHandler;
import com.brucecli.runtime.ConcurrencyConfig;
import com.brucecli.tool.CommandGuard;
import com.brucecli.tool.GuardedHitlToolRegistry;
import com.brucecli.integrated.cli.IntegratedCommandProcessor;
import com.brucecli.llm.ChatClient;
import com.brucecli.llm.ChatResponse;
import com.brucecli.llm.FunctionCall;
import com.brucecli.llm.Message;
import com.brucecli.llm.ToolDefinition;
import com.brucecli.llm.ToolCall;
import com.brucecli.memory.core.ConversationMemory;
import com.brucecli.memory.core.LongTermMemory;
import com.brucecli.memory.core.MemoryManager;
import com.brucecli.memory.model.MemoryEntry;
import com.brucecli.rag.embedding.EmbeddingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntegratedRuntimeTest {
    @TempDir
    Path tempDir;

    @Test
    void startsWithExpectedDefaultsAndSwitchesModesPersistently() throws Exception {
        try (TestContext context = context()) {
            RuntimeStatus status = context.runtime.status();
            assertEquals(AgentMode.REACT, status.mode());
            assertTrue(status.memoryEnabled());
            assertFalse(status.ragEnabled());
            assertTrue(status.webEnabled());
            assertTrue(status.hitlEnabled());
            assertTrue(status.parallelEnabled());
            assertEquals(4, status.maxParallelism());
            assertTrue(status.toolNames().contains("save_long_term_memory"));
            assertTrue(status.toolNames().contains("web_search"));
            assertTrue(status.toolNames().contains("web_fetch"));
            assertFalse(status.toolNames().contains("search_code"));

            context.commands.handle("/plan");
            assertEquals(AgentMode.PLAN, context.runtime.mode());
            context.commands.handle("/multi");
            assertEquals(AgentMode.MULTI, context.runtime.mode());
            context.commands.handle("/react");
            assertEquals(AgentMode.REACT, context.runtime.mode());

            context.commands.handle("/parallel off");
            assertFalse(context.runtime.parallelEnabled());
            assertTrue(context.commands.handle("/parallel status").output().contains("关闭"));
            context.commands.handle("/parallel on");
            assertTrue(context.runtime.parallelEnabled());
        }
    }

    @Test
    void webSwitchControlsToolsAndPrompt() throws Exception {
        try (TestContext context = context()) {
            assertTrue(context.runtime.status().toolNames().contains("web_search"));
            assertTrue(context.runtime.status().toolNames().contains("web_fetch"));
            context.runtime.run("需要查一个最新信息");
            assertTrue(context.chatClient.lastMessages.get(0).content().contains("web_search 和 web_fetch"));

            context.commands.handle("/web off");
            assertFalse(context.runtime.status().toolNames().contains("web_search"));
            assertFalse(context.runtime.status().toolNames().contains("web_fetch"));
            context.runtime.run("普通问题");
            assertFalse(context.chatClient.lastMessages.get(0).content().contains("web_search 和 web_fetch"));

            String disabled = context.commands.handle("/web search Java 21").output();
            assertTrue(disabled.contains("Web 当前关闭"));
        }
    }

    @Test
    void parallelSwitchControlsPrompt() throws Exception {
        try (TestContext context = context()) {
            context.runtime.run("普通问题");
            assertTrue(context.chatClient.lastMessages.get(0).content().contains("并行执行能力"));

            context.commands.handle("/parallel off");
            context.runtime.run("普通问题");
            assertFalse(context.chatClient.lastMessages.get(0).content().contains("并行执行能力"));
        }
    }

    @Test
    void ragSwitchControlsCommandsToolAndPrompt() throws Exception {
        try (TestContext context = context()) {
            String disabled = context.commands.handle("/index " + tempDir).output();
            assertTrue(disabled.contains("RAG 当前关闭"));

            context.commands.handle("/rag on");
            assertTrue(context.runtime.status().toolNames().contains("search_code"));
            context.runtime.run("代码在哪里实现？");
            assertTrue(context.chatClient.lastMessages.get(0).content().contains("search_code"));

            context.commands.handle("/rag off");
            assertFalse(context.runtime.status().toolNames().contains("search_code"));
            context.runtime.run("普通问题");
            assertFalse(context.chatClient.lastMessages.get(0).content().contains("你额外拥有 search_code"));
        }
    }

    @Test
    void memorySwitchRemovesToolButKeepsLongTermData() throws Exception {
        try (TestContext context = context()) {
            context.runtime.saveMemory("项目默认使用 JDK 17");

            context.commands.handle("/memory off");
            assertFalse(context.runtime.status().toolNames().contains("save_long_term_memory"));
            assertFalse(context.runtime.searchMemory("JDK 17", 5).isEmpty());

            context.commands.handle("/memory on");
            assertTrue(context.runtime.status().toolNames().contains("save_long_term_memory"));
            assertFalse(context.runtime.searchMemory("JDK 17", 5).isEmpty());
        }
    }

    @Test
    void reactToolCallsAreWrittenBackInOriginalOrderWithParallelExecutor() throws Exception {
        CapturingChatClient chatClient = new CapturingChatClient(
            new ChatResponse("", List.of(
                toolCall("call_b", "read_file", "{\"path\":\"b.txt\"}"),
                toolCall("call_a", "read_file", "{\"path\":\"a.txt\"}")
            )),
            text("done")
        );
        try (TestContext context = context(chatClient)) {
            Files.writeString(tempDir.resolve("a.txt"), "alpha");
            Files.writeString(tempDir.resolve("b.txt"), "bravo");

            context.commands.handle("/memory off");
            String result = context.runtime.run("读取两个文件");

            assertEquals("done", result);
            List<Message> toolMessages = chatClient.lastMessages.stream()
                .filter(message -> "tool".equals(message.role()))
                .toList();
            assertEquals(List.of("call_b", "call_a"), toolMessages.stream()
                .map(Message::toolCallId)
                .toList());
            assertTrue(toolMessages.get(0).content().contains("bravo"));
            assertTrue(toolMessages.get(1).content().contains("alpha"));
        }
    }

    @Test
    void planPromptReceivesParallelInstructionsOnlyWhenEnabled() throws Exception {
        CapturingChatClient chatClient = new CapturingChatClient(text("""
            {
              "goal": "分析",
              "tasks": [
                {"id": "t1", "description": "分析目标", "type": "ANALYSIS", "dependencies": []}
              ]
            }
            """));
        try (TestContext context = context(chatClient)) {
            context.commands.handle("/plan");
            context.runtime.run("分析项目");
            assertTrue(chatClient.lastMessages.get(0).content().contains("并行规划提示"));
        }

        CapturingChatClient serialChatClient = new CapturingChatClient(text("""
            {
              "goal": "分析",
              "tasks": [
                {"id": "t1", "description": "分析目标", "type": "ANALYSIS", "dependencies": []}
              ]
            }
            """));
        try (TestContext context = context(serialChatClient)) {
            context.commands.handle("/parallel off");
            context.commands.handle("/plan");
            context.runtime.run("分析项目");
            assertFalse(serialChatClient.lastMessages.get(0).content().contains("并行规划提示"));
        }
    }

    @Test
    void guardedToolRegistryRejectsUnsafeCommandsBeforeHitlForJsonAndMap() {
        EnabledHitlHandler handler = new EnabledHitlHandler();
        GuardedHitlToolRegistry registry = new GuardedHitlToolRegistry(
            handler,
            tempDir,
            new CommandGuard(),
            new ConcurrencyConfig(2, Duration.ofSeconds(1), 1_000)
        );

        String jsonResult = registry.executeTool(
            "execute_command",
            "{\"command\":\"find / -name pom.xml\"}"
        );
        String mapResult = registry.executeTool(
            "execute_command",
            Map.of("command", "grep -R LoginService /")
        );

        assertTrue(jsonResult.contains("命令被安全策略拒绝"));
        assertTrue(mapResult.contains("命令被安全策略拒绝"));
        assertEquals(0, handler.requestCount);

        String writeResult = registry.executeTool("write_file", Map.of(
            "path", "safe.txt",
            "content", "ok"
        ));
        assertTrue(writeResult.contains("文件已写入"));
        assertEquals(1, handler.requestCount);
    }

    @Test
    void indexingUpdatesWorkspaceAndMarksProjectIndexed() throws Exception {
        Path project = tempDir.resolve("project");
        Path source = project.resolve("src/main/java/demo/LoginService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package demo;
            public class LoginService {
                public boolean login(String user) {
                    return user != null;
                }
            }
            """);

        try (TestContext context = context()) {
            context.runtime.setRagEnabled(true);
            context.runtime.index(project, new PrintStream(OutputStream.nullOutputStream()));

            assertEquals(project.toAbsolutePath().normalize(), context.runtime.workspaceRoot());
            assertTrue(context.runtime.status().ragIndexed());
            assertTrue(context.runtime.searchCode("LoginService login", 3).contains("LoginService"));

            context.runtime.setRagEnabled(false);
            assertTrue(context.runtime.status().ragIndexed());
            assertFalse(context.runtime.status().toolNames().contains("search_code"));
        }
    }

    private TestContext context() throws Exception {
        CapturingChatClient chatClient = new CapturingChatClient();
        return context(chatClient);
    }

    private TestContext context(CapturingChatClient chatClient) throws Exception {
        MemoryManager memoryManager = new MemoryManager(
            new ConversationMemory(1_000),
            new LongTermMemory(tempDir.resolve("memory")),
            entries -> MemoryEntry.summary("测试摘要", Map.of("source_count", String.valueOf(entries.size())))
        );
        IntegratedRuntime runtime = new IntegratedRuntime(
            chatClient,
            tempDir,
            memoryManager,
            new FakeEmbeddingClient(),
            tempDir.resolve("rag/codebase.db"),
            new EnabledHitlHandler(),
            new ConcurrencyConfig(4, Duration.ofSeconds(2), 2_000)
        );
        return new TestContext(
            runtime,
            new IntegratedCommandProcessor(runtime, new PrintStream(OutputStream.nullOutputStream())),
            chatClient
        );
    }

    private static ChatResponse text(String content) {
        return new ChatResponse(content, List.of());
    }

    private static ToolCall toolCall(String id, String name, String arguments) {
        return new ToolCall(id, new FunctionCall(name, arguments));
    }

    private record TestContext(
        IntegratedRuntime runtime,
        IntegratedCommandProcessor commands,
        CapturingChatClient chatClient
    ) implements AutoCloseable {
        @Override
        public void close() {
            runtime.close();
        }
    }

    private static class CapturingChatClient implements ChatClient {
        private final Queue<ChatResponse> responses = new ArrayDeque<>();
        private List<Message> lastMessages = List.of();

        CapturingChatClient(ChatResponse... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<ToolDefinition> tools) {
            lastMessages = List.copyOf(messages);
            ChatResponse response = responses.poll();
            return response == null ? text("ok") : response;
        }
    }

    private static class FakeEmbeddingClient extends EmbeddingClient {
        FakeEmbeddingClient() {
            super("ollama", "fake", "http://localhost:11434", "");
        }

        @Override
        public float[] embed(String text) {
            String value = text == null ? "" : text.toLowerCase();
            return new float[] {
                value.contains("login") ? 1.0f : 0.0f,
                value.contains("service") ? 1.0f : 0.0f,
                value.contains("user") ? 1.0f : 0.0f
            };
        }
    }

    private static class EnabledHitlHandler implements HitlHandler {
        private boolean enabled = true;
        private int requestCount;

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public ApprovalResult requestApproval(ApprovalRequest request) {
            requestCount++;
            return ApprovalResult.approve();
        }
    }
}
