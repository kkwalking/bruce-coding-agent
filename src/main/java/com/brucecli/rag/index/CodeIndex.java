package com.brucecli.rag.index;

import com.brucecli.rag.chunk.CodeChunker;
import com.brucecli.rag.embedding.EmbeddingClient;
import com.brucecli.rag.graph.CodeAnalyzer;
import com.brucecli.rag.model.CodeChunk;
import com.brucecli.rag.model.CodeRelation;
import com.brucecli.rag.model.IndexProgress;
import com.brucecli.rag.model.IndexProgressListener;
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
        return index(projectPath, out, null);
    }

    public IndexStats index(Path projectPath, PrintStream out, IndexProgressListener progressListener)
        throws IOException, SQLException {
        long start = System.currentTimeMillis();
        Path root = projectPath.toAbsolutePath().normalize();
        CodeChunker chunker = new CodeChunker(root);
        CodeAnalyzer analyzer = new CodeAnalyzer(root);
        boolean legacyOutput = progressListener == null;
        IndexProgressListener listener = legacyOutput ? legacyProgressListener(out) : progressListener;

        List<Path> files = collectFiles(root);
        vectorStore.clearProject(root.toString());
        listener.onProgress(progress(root, 0, files.size(), 0, 0, "", 0, "indexing"));

        List<CodeChunk> allChunks = new ArrayList<>();
        List<float[]> embeddings = new ArrayList<>();
        List<CodeRelation> relations = new ArrayList<>();
        int processed = 0;
        int warnings = 0;

        for (Path file : files) {
            String relativeFile = root.relativize(file).toString();
            try {
                List<CodeChunk> chunks = chunker.chunkFile(file);
                for (CodeChunk chunk : chunks) {
                    allChunks.add(chunk);
                    embeddings.add(embeddingClient.embed(chunk.toEmbeddingText()));
                }
                relations.addAll(analyzer.analyzeFile(file));
            } catch (Exception e) {
                warnings++;
                if (legacyOutput && out != null) {
                    out.println("[warn] 跳过文件 " + relativeFile + ": " + e.getMessage());
                }
            }

            processed++;
            listener.onProgress(progress(
                root,
                processed,
                files.size(),
                allChunks.size(),
                relations.size(),
                relativeFile,
                warnings,
                "indexing"
            ));
        }

        listener.onProgress(progress(
            root,
            processed,
            files.size(),
            allChunks.size(),
            relations.size(),
            "",
            warnings,
            "saving"
        ));
        vectorStore.saveChunks(allChunks, embeddings);
        vectorStore.saveRelations(relations);
        IndexStats stats = new IndexStats(
            root.toString(),
            files.size(),
            allChunks.size(),
            relations.size(),
            System.currentTimeMillis() - start
        );
        listener.onProgress(progress(
            root,
            processed,
            files.size(),
            allChunks.size(),
            relations.size(),
            "",
            warnings,
            "done"
        ));
        return stats;
    }

    private static IndexProgress progress(
        Path root,
        int processed,
        int total,
        int chunks,
        int relations,
        String currentFile,
        int warnings,
        String phase
    ) {
        return new IndexProgress(root.toString(), processed, total, chunks, relations, currentFile, warnings, phase);
    }

    private static IndexProgressListener legacyProgressListener(PrintStream out) {
        if (out == null) {
            return IndexProgressListener.noop();
        }
        return progress -> {
            if ("indexing".equals(progress.phase())
                && progress.processedFiles() > 0
                && progress.processedFiles() % 10 == 0) {
                out.printf("[index] 已处理 %d/%d 个文件%n", progress.processedFiles(), progress.totalFiles());
            }
        };
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
