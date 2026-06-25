package com.brucecli.integrated.cli;

import com.brucecli.approval.LanternaHitlHandler;
import com.brucecli.integrated.runtime.IntegratedRuntime;
import com.brucecli.llm.ChatClient;
import com.brucecli.llm.ChatClientFactory;
import com.brucecli.memory.compress.LlmContextCompressor;
import com.brucecli.memory.core.ConversationMemory;
import com.brucecli.memory.core.LongTermMemory;
import com.brucecli.memory.core.MemoryManager;
import com.brucecli.rag.embedding.EmbeddingClient;
import com.brucecli.rag.store.VectorStore;
import com.brucecli.render.LanternaBruceRenderer;
import com.brucecli.runtime.ConcurrencyConfig;
import com.brucecli.web.search.WebSearchConfig;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.terminal.DefaultTerminalFactory;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class IntegratedMain {
    public static void main(String[] args) throws Exception {
        DefaultTerminalFactory terminalFactory = new DefaultTerminalFactory()
            .setInputTimeout(50);
        try (Screen screen = terminalFactory.createScreen()) {
            LanternaBruceRenderer renderer = new LanternaBruceRenderer(screen);
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
            PrintStream tuiStream = renderer.stream();
            System.setOut(tuiStream);
            System.setErr(tuiStream);
            try {
                LanternaHitlHandler hitlHandler = new LanternaHitlHandler(true, renderer);
                EnvLoader env = new EnvLoader();

                ChatClient chatClient;
                try {
                    chatClient = ChatClientFactory.create(env::get);
                } catch (IllegalArgumentException exception) {
                    originalErr.println(exception.getMessage());
                    System.exit(1);
                    return;
                }
                MemoryManager memoryManager = new MemoryManager(
                    new ConversationMemory(8_000),
                    new LongTermMemory(),
                    new LlmContextCompressor(chatClient)
                );
                EmbeddingClient embeddingClient = new EmbeddingClient(
                    env.get("EMBEDDING_PROVIDER"),
                    env.get("EMBEDDING_MODEL"),
                    env.get("EMBEDDING_BASE_URL"),
                    env.get("EMBEDDING_API_KEY")
                );
                WebSearchConfig webSearchConfig = new WebSearchConfig(
                    firstNonBlank(env.get("WEB_SEARCH_PROVIDER"), env.get("SEARCH_PROVIDER")),
                    env.get("GLM_API_KEY"),
                    env.get("GLM_SEARCH_ENGINE"),
                    env.get("GLM_SEARCH_CONTENT_SIZE"),
                    env.get("SERPAPI_KEY"),
                    env.get("SEARXNG_URL"),
                    env.get("GLM_WEB_SEARCH_ENDPOINT")
                );

                try (IntegratedRuntime runtime = new IntegratedRuntime(
                    chatClient,
                    Path.of("").toAbsolutePath().normalize(),
                    memoryManager,
                    embeddingClient,
                    VectorStore.defaultDbPath(),
                    hitlHandler,
                    webSearchConfig,
                    ConcurrencyConfig.defaults(),
                    Path.of(System.getProperty("user.home")),
                    renderer.stream()
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
                        commands,
                        Path.of(System.getProperty("user.home"))
                    ).run();
                }
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
