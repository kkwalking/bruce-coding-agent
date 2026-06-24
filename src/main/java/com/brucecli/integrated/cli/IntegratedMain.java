package com.brucecli.integrated.cli;

import com.brucecli.approval.TerminalHitlHandler;
import com.brucecli.integrated.runtime.IntegratedRuntime;
import com.brucecli.llm.ChatClient;
import com.brucecli.llm.ChatClientFactory;
import com.brucecli.memory.compress.LlmContextCompressor;
import com.brucecli.memory.core.ConversationMemory;
import com.brucecli.memory.core.LongTermMemory;
import com.brucecli.memory.core.MemoryManager;
import com.brucecli.rag.embedding.EmbeddingClient;
import com.brucecli.rag.store.VectorStore;
import com.brucecli.runtime.ConcurrencyConfig;
import com.brucecli.web.search.WebSearchConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class IntegratedMain {
    public static void main(String[] args) throws Exception {
        printBanner();
        EnvLoader env = new EnvLoader();

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8)
        );
        TerminalHitlHandler hitlHandler = new TerminalHitlHandler(true, reader, System.out);
        ChatClient chatClient;
        try {
            chatClient = ChatClientFactory.create(env::get);
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
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
            ConcurrencyConfig.defaults()
        )) {
            IntegratedCommandProcessor commands = new IntegratedCommandProcessor(runtime, System.out);
            System.out.println(commands.help());
            while (true) {
                System.out.print("\n你[" + runtime.mode().name().toLowerCase() + "]: ");
                System.out.flush();
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                String input = line.trim();
                if (input.isEmpty()) {
                    continue;
                }

                CommandResult command = commands.handle(input);
                if (command.exit()) {
                    break;
                }
                if (command.handled()) {
                    if (!command.output().isBlank()) {
                        System.out.println(command.output());
                    }
                    continue;
                }

                System.out.println("思考中...");
                try {
                    System.out.println(runtime.run(input));
                } catch (Exception e) {
                    System.out.println("执行失败: " + e.getMessage());
                }
            }
        }
    }

    private static void printBanner() {
        System.out.println("""
            ==========================================================
                        Bruce CLI - Integrated Agent
            ==========================================================
            """);
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
