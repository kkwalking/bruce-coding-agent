package com.brucecli.rag.chunk;

import com.brucecli.rag.model.CodeChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CodeChunkerTest {
    @TempDir
    Path tempDir;

    @Test
    void chunksJavaFileByClassAndMethod() throws Exception {
        Path javaFile = writeAgentSource(tempDir);

        List<CodeChunk> chunks = new CodeChunker(tempDir).chunkFile(javaFile);

        assertTrue(chunks.stream().anyMatch(chunk ->
            chunk.chunkType().equals("class") && chunk.name().equals("Agent")));
        CodeChunk runChunk = chunks.stream()
            .filter(chunk -> chunk.chunkType().equals("method"))
            .filter(chunk -> chunk.name().contains("run()"))
            .findFirst()
            .orElseThrow();
        assertTrue(runChunk.content().contains("executeTool"));
        assertTrue(runChunk.toEmbeddingText().startsWith("[method:Agent."));
    }

    @Test
    void chunksLargeTextFilesByMaxCharacters() throws Exception {
        Path docs = tempDir.resolve("README.md");
        Files.writeString(docs, "a".repeat(CodeChunker.MAX_CHUNK_CHARS + 37));

        List<CodeChunk> chunks = new CodeChunker(tempDir).chunkFile(docs);

        assertEquals(2, chunks.size());
        assertTrue(chunks.stream().allMatch(chunk -> chunk.chunkType().equals("file")));
    }

    public static Path writeAgentSource(Path root) throws Exception {
        Path source = root.resolve("src/main/java/demo/Agent.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
            package demo;

            import demo.tools.ToolRegistry;
            import java.util.List;

            public class Agent extends BaseAgent implements Runnable {
                private final Memory memory = new Memory();

                @Override
                public void run() {
                    plan();
                    executeTool("search_code");
                }

                private void plan() {
                    memory.save("step");
                }
            }
            """);
        return source;
    }
}
