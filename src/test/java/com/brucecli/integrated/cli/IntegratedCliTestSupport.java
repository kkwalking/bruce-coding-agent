package com.brucecli.integrated.cli;

import com.brucecli.approval.ApprovalRequest;
import com.brucecli.approval.ApprovalResult;
import com.brucecli.approval.HitlHandler;
import com.brucecli.config.BruceSettingsLoader;
import com.brucecli.integrated.runtime.IntegratedRuntime;
import com.brucecli.llm.ChatClient;
import com.brucecli.llm.ChatResponse;
import com.brucecli.llm.Message;
import com.brucecli.llm.ModelOption;
import com.brucecli.llm.ModelSelectionService;
import com.brucecli.llm.ToolDefinition;
import com.brucecli.memory.core.ConversationMemory;
import com.brucecli.memory.core.LongTermMemory;
import com.brucecli.memory.core.MemoryManager;
import com.brucecli.memory.model.MemoryEntry;
import com.brucecli.mcp.config.McpConfig;
import com.brucecli.mcp.config.McpConfigLoader;
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
        return context(tempDir, out, new CapturingChatClient());
    }

    public static TestContext context(Path tempDir, PrintStream out, ChatClient chatClient) throws Exception {
        Path testHome = tempDir.resolve("home");
        BruceSettingsLoader settingsLoader = new BruceSettingsLoader(testHome.resolve(".bruce/setting.json"));
        McpConfig mcpConfig = new McpConfigLoader(tempDir, settingsLoader).load();
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
            testHome,
            out,
            mcpConfig
        );
        return new TestContext(
            runtime,
            new IntegratedCommandProcessor(runtime, out),
            chatClient
        );
    }

    public static Path writeSkill(Path tempDir, String name, String description, String instructions) throws Exception {
        Path directory = tempDir.resolve(".bruce/skills").resolve(name);
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
        Path config = tempDir.resolve("home/.bruce/setting.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, """
            {
              "mcp": {
                "servers": {
                  "%s": {
                    "command": "echo",
                    "args": ["ok"],
                    "disabled": true
                  }
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

    static class CapturingChatClient implements ChatClient, ModelSelectionService {
        private ModelOption currentModel = new ModelOption("test", "test-model");
        private final List<ModelOption> models = List.of(
            new ModelOption("test", "test-model"),
            new ModelOption("glm", "glm-5.1")
        );

        @Override
        public ChatResponse chat(List<Message> messages, List<ToolDefinition> tools) {
            return new ChatResponse("ok", List.of());
        }

        @Override
        public String getProviderName() {
            return currentModel.provider();
        }

        @Override
        public String getModelName() {
            return currentModel.model();
        }

        @Override
        public List<ModelOption> modelOptions() {
            return models;
        }

        @Override
        public ModelOption currentModel() {
            return currentModel;
        }

        @Override
        public ModelOption switchModel(String selector) {
            currentModel = models.stream()
                .filter(option -> option.selector().equalsIgnoreCase(selector)
                    || option.model().equalsIgnoreCase(selector))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知模型: " + selector));
            return currentModel;
        }

        @Override
        public String settingsPath() {
            return "";
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
