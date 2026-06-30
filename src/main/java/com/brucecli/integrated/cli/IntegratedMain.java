package com.brucecli.integrated.cli;

import com.brucecli.config.BruceSettings;
import com.brucecli.config.BruceSettingsLoader;
import com.brucecli.integrated.runtime.IntegratedRuntime;
import com.brucecli.llm.ChatClient;
import com.brucecli.llm.ChatClientFactory;
import com.brucecli.memory.compress.LlmContextCompressor;
import com.brucecli.memory.core.ConversationMemory;
import com.brucecli.memory.core.LongTermMemory;
import com.brucecli.memory.core.MemoryManager;
import com.brucecli.mcp.config.McpConfig;
import com.brucecli.mcp.config.McpConfigLoader;
import com.brucecli.rag.embedding.EmbeddingClient;
import com.brucecli.runtime.ConcurrencyConfig;
import com.brucecli.tui.BruceTuiApp;
import com.brucecli.tui.LanternaBruceRenderer;
import com.brucecli.tui.LanternaHitlHandler;
import com.brucecli.tui.TuiCommandResult;
import com.brucecli.web.search.WebSearchConfig;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;
import com.googlecode.lanterna.terminal.MouseCaptureMode;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class IntegratedMain {
    public static void main(String[] args) throws Exception {
        DefaultTerminalFactory terminalFactory = new DefaultTerminalFactory()
            .setInputTimeout(50)
            .setMouseCaptureMode(MouseCaptureMode.CLICK_RELEASE);
        try (Screen screen = terminalFactory.createScreen()) {
            LanternaBruceRenderer renderer = new LanternaBruceRenderer(screen);
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            PrintStream tuiStream = renderer.stream();
            System.setOut(tuiStream);
            System.setErr(tuiStream);
            try {
                LanternaHitlHandler hitlHandler = new LanternaHitlHandler(true, renderer);
                Path workspaceRoot = Path.of("").toAbsolutePath().normalize();
                Path userHome = Path.of(System.getProperty("user.home"));
                BruceSettingsLoader settingsLoader = BruceSettingsLoader.defaults();

                BruceSettings settings;
                ChatClient chatClient;
                McpConfig mcpConfig;
                try {
                    settings = settingsLoader.load();
                    chatClient = ChatClientFactory.create(settings, settingsLoader);
                    mcpConfig = new McpConfigLoader(workspaceRoot, settings, settingsLoader.settingsFile()).load();
                } catch (Exception exception) {
                    originalErr.println(exception.getMessage());
                    System.exit(1);
                    return;
                }
                MemoryManager memoryManager = new MemoryManager(
                    new ConversationMemory(8_000),
                    new LongTermMemory(BruceSettingsLoader.resolveUserPath(settings.getStorage().getMemoryDir())),
                    new LlmContextCompressor(chatClient)
                );
                EmbeddingClient embeddingClient = EmbeddingClient.fromSettings(settings.getEmbedding());
                WebSearchConfig webSearchConfig = WebSearchConfig.fromSettings(settings.getWebSearch());
                Path ragDbFile = BruceSettingsLoader.resolveUserPath(settings.getStorage().getRagDir()).resolve("codebase.db");

                try (IntegratedRuntime runtime = new IntegratedRuntime(
                    chatClient,
                    workspaceRoot,
                    memoryManager,
                    embeddingClient,
                    ragDbFile,
                    hitlHandler,
                    webSearchConfig,
                    ConcurrencyConfig.defaults(),
                    userHome,
                    renderer.stream(),
                    mcpConfig
                )) {
                    IntegratedCommandProcessor commands = new IntegratedCommandProcessor(
                        runtime,
                        renderer.stream(),
                        renderer::updateIndexProgress
                    );
                    new BruceTuiApp(
                        screen,
                        renderer,
                        runtime,
                        chatClient,
                        input -> {
                            CommandResult result = commands.handle(input);
                            return new TuiCommandResult(result.handled(), result.exit(), result.output());
                        },
                        userHome
                    ).run();
                }
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        }
    }

}
