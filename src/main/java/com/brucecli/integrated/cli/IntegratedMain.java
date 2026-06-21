package com.brucecli.integrated.cli;

import com.brucecli.approval.TerminalHitlHandler;
import com.brucecli.integrated.runtime.IntegratedRuntime;
import com.brucecli.llm.DeepSeekClient;
import com.brucecli.memory.compress.LlmContextCompressor;
import com.brucecli.memory.core.ConversationMemory;
import com.brucecli.memory.core.LongTermMemory;
import com.brucecli.memory.core.MemoryManager;
import com.brucecli.rag.embedding.EmbeddingClient;
import com.brucecli.rag.store.VectorStore;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class IntegratedMain {
    public static void main(String[] args) throws Exception {
        printBanner();
        EnvLoader env = new EnvLoader();
        String apiKey = env.get("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("错误: 未找到 DEEPSEEK_API_KEY");
            System.err.println("请在项目根目录的 .env 中配置，或设置同名环境变量。");
            System.exit(1);
        }

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(System.in, StandardCharsets.UTF_8)
        );
        TerminalHitlHandler hitlHandler = new TerminalHitlHandler(true, reader, System.out);
        DeepSeekClient chatClient = new DeepSeekClient(apiKey, env.get("DEEPSEEK_MODEL"));
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

        try (IntegratedRuntime runtime = new IntegratedRuntime(
            chatClient,
            Path.of("").toAbsolutePath().normalize(),
            memoryManager,
            embeddingClient,
            VectorStore.defaultDbPath(),
            hitlHandler
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
}
