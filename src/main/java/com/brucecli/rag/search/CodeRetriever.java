package com.brucecli.rag.search;

import com.brucecli.rag.embedding.EmbeddingClient;
import com.brucecli.rag.store.VectorStore;
import com.brucecli.rag.store.VectorStore.SearchResult;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 检索入口。
 *
 * <p>hybridSearch = 语义检索 + 关键词检索 + 类型加分 + 双重命中奖励 + 同文件限流。</p>
 */
public class CodeRetriever {
    private static final int DEFAULT_MAX_PER_FILE = 2;

    private final String projectPath;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final RagQueryTokenizer tokenizer = new RagQueryTokenizer();

    public CodeRetriever(String projectPath, EmbeddingClient embeddingClient, VectorStore vectorStore) {
        this.projectPath = projectPath;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
    }

    public List<SearchResult> hybridSearch(String query, int topK) throws IOException, SQLException {
        int size = Math.max(1, topK);
        if (!vectorStore.hasProject(projectPath)) {
            return List.of();
        }

        float[] queryEmbedding = embeddingClient.embed(query);
        List<SearchResult> semantic = vectorStore.search(projectPath, queryEmbedding, size * 4);
        List<SearchResult> keyword = vectorStore.keywordSearch(
            projectPath,
            new ArrayList<>(tokenizer.tokenize(query)),
            size * 4
        );

        Map<String, MutableScore> merged = new LinkedHashMap<>();
        for (SearchResult result : semantic) {
            merged.computeIfAbsent(result.key(), ignored -> new MutableScore(result))
                .semantic(result.similarity());
        }
        for (SearchResult result : keyword) {
            merged.computeIfAbsent(result.key(), ignored -> new MutableScore(result))
                .keyword(result.keywordScore());
        }

        List<SearchResult> sorted = merged.values().stream()
            .map(MutableScore::toResult)
            .sorted(Comparator.comparingDouble(SearchResult::finalScore).reversed())
            .toList();
        return limitPerFile(sorted, size, DEFAULT_MAX_PER_FILE);
    }

    private List<SearchResult> limitPerFile(List<SearchResult> sorted, int topK, int maxPerFile) {
        List<SearchResult> result = new ArrayList<>();
        Map<String, Integer> fileCount = new HashMap<>();
        for (SearchResult r : sorted) {
            int count = fileCount.getOrDefault(r.filePath(), 0);
            if (count < maxPerFile) {
                result.add(r);
                fileCount.put(r.filePath(), count + 1);
                if (result.size() >= topK) {
                    break;
                }
            }
        }
        return result;
    }

    private static double typeBoost(String chunkType) {
        return switch (chunkType) {
            case "method" -> 0.15;
            case "class" -> 0.10;
            default -> 0.0;
        };
    }

    private static class MutableScore {
        private final SearchResult source;
        private double semanticScore;
        private double keywordScore;
        private boolean semanticHit;
        private boolean keywordHit;

        MutableScore(SearchResult source) {
            this.source = source;
        }

        void semantic(double score) {
            semanticScore = Math.max(semanticScore, score);
            semanticHit = true;
        }

        void keyword(double score) {
            keywordScore = Math.max(keywordScore, score);
            keywordHit = true;
        }

        SearchResult toResult() {
            double finalScore = semanticScore + keywordScore + typeBoost(source.chunkType());
            if (semanticHit && keywordHit) {
                finalScore += 0.10;
            }
            return source.withScores(semanticScore, keywordScore, finalScore);
        }
    }
}
