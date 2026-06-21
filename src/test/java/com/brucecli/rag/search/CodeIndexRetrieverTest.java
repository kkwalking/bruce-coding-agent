package com.brucecli.rag.search;

import com.brucecli.rag.chunk.CodeChunkerTest;
import com.brucecli.rag.embedding.EmbeddingClient;
import com.brucecli.rag.index.CodeIndex;
import com.brucecli.rag.model.IndexStats;
import com.brucecli.rag.store.VectorStore;
import com.brucecli.rag.store.VectorStore.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeIndexRetrieverTest {
    @TempDir
    Path tempDir;

    @Test
    void indexesCodeAndRetrievesByHybridSearchWithoutNetwork() throws Exception {
        Path project = tempDir.resolve("sample-agent");
        CodeChunkerTest.writeAgentSource(project);
        Files.writeString(project.resolve("README.md"), "Agent uses search_code to answer code questions.");

        EmbeddingClient embeddingClient = new FakeEmbeddingClient();
        try (VectorStore vectorStore = new VectorStore(tempDir.resolve("codebase.db"))) {
            CodeIndex index = new CodeIndex(embeddingClient, vectorStore);
            IndexStats stats = index.index(project, new PrintStream(OutputStream.nullOutputStream()));

            String projectPath = project.toAbsolutePath().normalize().toString();
            assertTrue(stats.chunks() >= 3);
            assertTrue(vectorStore.hasProject(projectPath));

            CodeRetriever retriever = new CodeRetriever(projectPath, embeddingClient, vectorStore);
            List<SearchResult> results = retriever.hybridSearch("Agent run executeTool search_code", 5);

            assertFalse(results.isEmpty());
            assertTrue(results.stream().anyMatch(result ->
                result.name().contains("Agent") && result.content().contains("executeTool")));

            assertTrue(SearchResultFormatter.formatForTool("Agent run", results).contains("最相关入口"));
        }
    }

    private static class FakeEmbeddingClient extends EmbeddingClient {
        FakeEmbeddingClient() {
            super("ollama", "fake-embedding", "http://localhost:11434", "");
        }

        @Override
        public float[] embed(String text) {
            String value = text == null ? "" : text.toLowerCase();
            return new float[] {
                value.contains("agent") ? 1.0f : 0.0f,
                value.contains("run") ? 1.0f : 0.0f,
                value.contains("executetool") ? 1.0f : 0.0f,
                value.contains("search_code") ? 1.0f : 0.0f
            };
        }
    }
}
