package com.brucecli.rag.index;

import com.brucecli.rag.chunk.CodeChunker;
import com.brucecli.rag.embedding.EmbeddingClient;
import com.brucecli.rag.graph.CodeAnalyzer;
import com.brucecli.rag.model.CodeChunk;
import com.brucecli.rag.model.CodeRelation;
import com.brucecli.rag.model.IndexStats;
import com.brucecli.rag.store.VectorStore;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 索引入口：遍历文件、分块、向量化、持久化。
 */
public class CodeIndex {
    private static final Set<String> SKIP_DIRS = Set.of(
        ".git", ".idea", ".vscode", "target", "build", "node_modules", "dist", "out"
    );
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
        ".java", ".xml", ".md", ".yml", ".yaml", ".properties", ".json", ".txt", ".js", ".ts", ".jsx", ".tsx"
    );

    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;

    public CodeIndex(EmbeddingClient embeddingClient, VectorStore vectorStore) {
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
    }

    public IndexStats index(Path projectPath, PrintStream out) throws IOException, SQLException {
        long start = System.currentTimeMillis();
        Path root = projectPath.toAbsolutePath().normalize();
        CodeChunker chunker = new CodeChunker(root);
        CodeAnalyzer analyzer = new CodeAnalyzer(root);

        List<Path> files = collectFiles(root);
        vectorStore.clearProject(root.toString());

        List<CodeChunk> allChunks = new ArrayList<>();
        List<float[]> embeddings = new ArrayList<>();
        List<CodeRelation> relations = new ArrayList<>();
        int processed = 0;

        for (Path file : files) {
            try {
                List<CodeChunk> chunks = chunker.chunkFile(file);
                for (CodeChunk chunk : chunks) {
                    allChunks.add(chunk);
                    embeddings.add(embeddingClient.embed(chunk.toEmbeddingText()));
                }
                relations.addAll(analyzer.analyzeFile(file));
            } catch (Exception e) {
                out.println("[warn] 跳过文件 " + root.relativize(file) + ": " + e.getMessage());
            }

            processed++;
            if (processed % 10 == 0) {
                out.printf("[index] 已处理 %d/%d 个文件%n", processed, files.size());
            }
        }

        vectorStore.saveChunks(allChunks, embeddings);
        vectorStore.saveRelations(relations);
        return new IndexStats(
            root.toString(),
            files.size(),
            allChunks.size(),
            relations.size(),
            System.currentTimeMillis() - start
        );
    }

    private List<Path> collectFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && SKIP_DIRS.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (isSourceFile(file)) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private boolean isSourceFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return SOURCE_EXTENSIONS.stream().anyMatch(name::endsWith);
    }
}
