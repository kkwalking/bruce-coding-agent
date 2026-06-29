package com.brucecli.integrated.cli;

import com.brucecli.approval.ApprovalRequest;
import com.brucecli.approval.ApprovalResult;
import com.brucecli.approval.HitlHandler;
import com.brucecli.integrated.runtime.IntegratedRuntime;
import com.brucecli.llm.ChatClient;
import com.brucecli.llm.ChatResponse;
import com.brucecli.llm.Message;
import com.brucecli.llm.ToolDefinition;
import com.brucecli.memory.core.ConversationMemory;
import com.brucecli.memory.core.LongTermMemory;
import com.brucecli.memory.core.MemoryManager;
import com.brucecli.memory.model.MemoryEntry;
import com.brucecli.rag.embedding.EmbeddingClient;
import com.brucecli.runtime.ConcurrencyConfig;
import com.brucecli.web.search.WebSearchConfig;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class IntegratedCliTestSupport {
    private IntegratedCliTestSupport() {
    }

    public static TestContext context(Path tempDir) throws Exception {
        return context(tempDir, new PrintStream(OutputStream.nullOutputStream()));
    }

    public static TestContext context(Path tempDir, PrintStream out) throws Exception {
        CapturingChatClient chatClient = new CapturingChatClient();
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
            WebSearchConfig.empty(),
            new ConcurrencyConfig(4, Duration.ofSeconds(2), 2_000),
            tempDir.resolve("home"),
            out
        );
        return new TestContext(
            runtime,
            new IntegratedCommandProcessor(runtime, out),
            chatClient
        );
    }

    public static Path writeSkill(Path tempDir, String name, String description, String instructions) throws Exception {
        Path directory = tempDir.resolve(".brucecli/skills").resolve(name);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), """
            ---
            name: %s
            description: %s
            ---

            %s
            """.formatted(name, description, instructions));
        return directory;
    }

    public static void writeDisabledMcpServer(Path tempDir, String name) throws Exception {
        Path config = tempDir.resolve(".brucecli/mcp.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
            {
              "mcpServers": {
                "%s": {
                  "command": "echo",
                  "args": ["ok"],
                  "disabled": true
                }
              }
            }
            """.formatted(name));
    }

    public record TestContext(
        IntegratedRuntime runtime,
        IntegratedCommandProcessor commands,
        ChatClient chatClient
    ) implements AutoCloseable {
        @Override
        public void close() {
            runtime.close();
        }
    }

    static class CapturingChatClient implements ChatClient {
        @Override
        public ChatResponse chat(List<Message> messages, List<ToolDefinition> tools) {
            return new ChatResponse("ok", List.of());
        }
    }

    private static class FakeEmbeddingClient extends EmbeddingClient {
        FakeEmbeddingClient() {
            super("ollama", "fake", "http://localhost:11434", "");
        }

        @Override
        public float[] embed(String text) {
            return new float[] {1.0f, 0.0f, 0.0f};
        }
    }

    private static class EnabledHitlHandler implements HitlHandler {
        private boolean enabled = true;

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
            return ApprovalResult.approve();
        }
    }
}
