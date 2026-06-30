package com.brucecli.rag.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.brucecli.rag.model.CodeChunk;
import com.brucecli.rag.model.CodeRelation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SQLite 向量存储。
 *
 * <p>向量以 JSON 数组存在 TEXT 字段里。对 CLI 学习项目来说，
 * 几千个 chunk 全量读到内存算余弦相似度已经足够轻量。</p>
 */
public class VectorStore implements AutoCloseable {
    public static final String JVM_RAG_DIR = "bruce.rag.dir";
    public static final String ENV_RAG_DIR = "BRUCE_RAG_DIR";

    private final Path dbFile;
    private final Connection connection;
    private final ObjectMapper mapper = new ObjectMapper();

    public VectorStore(Path dbFile) throws SQLException, IOException {
        this.dbFile = dbFile.toAbsolutePath().normalize();
        Path parent = this.dbFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.dbFile);
        initSchema();
    }

    public static Path defaultDbPath() {
        String jvmValue = System.getProperty(JVM_RAG_DIR);
        if (jvmValue != null && !jvmValue.isBlank()) {
            return Path.of(jvmValue).toAbsolutePath().normalize().resolve("codebase.db");
        }
        String envValue = System.getenv(ENV_RAG_DIR);
        if (envValue != null && !envValue.isBlank()) {
            return Path.of(envValue).toAbsolutePath().normalize().resolve("codebase.db");
        }
        return Path.of(System.getProperty("user.home"), ".bruce", "rag", "codebase.db")
            .toAbsolutePath()
            .normalize();
    }

    public void clearProject(String projectPath) throws SQLException {
        try (PreparedStatement chunks = connection.prepareStatement("DELETE FROM code_chunks WHERE project_path = ?");
             PreparedStatement relations = connection.prepareStatement("DELETE FROM code_relations WHERE project_path = ?")) {
            chunks.setString(1, projectPath);
            chunks.executeUpdate();
            relations.setString(1, projectPath);
            relations.executeUpdate();
        }
    }

    public void saveChunks(List<CodeChunk> chunks, List<float[]> embeddings) throws SQLException, IOException {
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException("chunks 和 embeddings 数量不一致");
        }
        String sql = """
            INSERT INTO code_chunks(project_path, file_path, chunk_type, name, content, start_line, end_line, embedding_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
        boolean oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (int i = 0; i < chunks.size(); i++) {
                CodeChunk chunk = chunks.get(i);
                ps.setString(1, chunk.projectPath());
                ps.setString(2, chunk.filePath());
                ps.setString(3, chunk.chunkType());
                ps.setString(4, chunk.name());
                ps.setString(5, chunk.content());
                ps.setInt(6, chunk.startLine());
                ps.setInt(7, chunk.endLine());
                ps.setString(8, embeddingToJson(embeddings.get(i)));
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException | IOException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    public void saveRelations(List<CodeRelation> relations) throws SQLException {
        String sql = """
            INSERT INTO code_relations(project_path, from_file_path, from_name, to_file_path, to_name, relation_type)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
        boolean oldAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            for (CodeRelation relation : relations) {
                ps.setString(1, relation.projectPath());
                ps.setString(2, relation.fromFilePath());
                ps.setString(3, relation.fromName());
                ps.setString(4, relation.toFilePath());
                ps.setString(5, relation.toName());
                ps.setString(6, relation.relationType());
                ps.addBatch();
            }
            ps.executeBatch();
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    public List<SearchResult> search(String projectPath, float[] queryEmbedding, int topK) throws SQLException, IOException {
        String sql = """
            SELECT project_path, file_path, chunk_type, name, content, start_line, end_line, embedding_json
            FROM code_chunks
            WHERE project_path = ?
            """;
        List<SearchResult> candidates = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    float[] embedding = jsonToEmbedding(rs.getString("embedding_json"));
                    double similarity = cosineSimilarity(queryEmbedding, embedding);
                    candidates.add(fromResultSet(rs, similarity, 0.0, similarity));
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(SearchResult::finalScore).reversed());
        return limit(candidates, topK);
    }

    public List<SearchResult> keywordSearch(String projectPath, List<String> terms, int limit) throws SQLException {
        if (terms == null || terms.isEmpty()) {
            return List.of();
        }

        List<SearchResult> all = chunks(projectPath);
        Map<String, SearchResult> scored = new LinkedHashMap<>();
        for (SearchResult result : all) {
            double score = keywordScore(result, terms);
            if (score <= 0) {
                continue;
            }
            scored.put(result.key(), result.withScores(0.0, score, score));
        }
        return scored.values().stream()
            .sorted(Comparator.comparingDouble(SearchResult::finalScore).reversed())
            .limit(Math.max(1, limit))
            .toList();
    }

    public List<CodeRelation> relations(String projectPath, String name) throws SQLException {
        String like = "%" + name.toLowerCase(Locale.ROOT) + "%";
        String sql = """
            SELECT project_path, from_file_path, from_name, to_file_path, to_name, relation_type
            FROM code_relations
            WHERE project_path = ?
              AND (lower(from_name) LIKE ? OR lower(to_name) LIKE ?)
            ORDER BY from_name, relation_type, to_name
            """;
        List<CodeRelation> relations = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            ps.setString(2, like);
            ps.setString(3, like);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    relations.add(new CodeRelation(
                        rs.getString("project_path"),
                        rs.getString("from_file_path"),
                        rs.getString("from_name"),
                        rs.getString("to_file_path"),
                        rs.getString("to_name"),
                        rs.getString("relation_type")
                    ));
                }
            }
        }
        return relations;
    }

    public boolean hasProject(String projectPath) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT 1 FROM code_chunks WHERE project_path = ? LIMIT 1")) {
            ps.setString(1, projectPath);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private List<SearchResult> chunks(String projectPath) throws SQLException {
        String sql = """
            SELECT project_path, file_path, chunk_type, name, content, start_line, end_line
            FROM code_chunks
            WHERE project_path = ?
            """;
        List<SearchResult> results = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectPath);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(fromResultSet(rs, 0.0, 0.0, 0.0));
                }
            }
        }
        return results;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    private void initSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS code_chunks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_path TEXT NOT NULL,
                    file_path TEXT NOT NULL,
                    chunk_type TEXT NOT NULL,
                    name TEXT NOT NULL,
                    content TEXT NOT NULL,
                    start_line INTEGER NOT NULL,
                    end_line INTEGER NOT NULL,
                    embedding_json TEXT NOT NULL
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS code_relations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_path TEXT NOT NULL,
                    from_file_path TEXT NOT NULL,
                    from_name TEXT NOT NULL,
                    to_file_path TEXT NOT NULL,
                    to_name TEXT NOT NULL,
                    relation_type TEXT NOT NULL
                )
                """);
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_code_chunks_project ON code_chunks(project_path)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_code_chunks_file ON code_chunks(project_path, file_path)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_code_chunks_type ON code_chunks(project_path, chunk_type)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_relations_from ON code_relations(project_path, from_name)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_relations_to ON code_relations(project_path, to_name)");
        }
    }

    private SearchResult fromResultSet(ResultSet rs, double similarity, double keywordScore, double finalScore)
        throws SQLException {
        return new SearchResult(
            rs.getString("project_path"),
            rs.getString("file_path"),
            rs.getString("chunk_type"),
            rs.getString("name"),
            rs.getString("content"),
            rs.getInt("start_line"),
            rs.getInt("end_line"),
            similarity,
            keywordScore,
            finalScore
        );
    }

    private double keywordScore(SearchResult result, List<String> terms) {
        double score = 0.0;
        String name = result.name().toLowerCase(Locale.ROOT);
        String filePath = result.filePath().toLowerCase(Locale.ROOT);
        String content = result.content().toLowerCase(Locale.ROOT);
        for (String term : terms) {
            String normalized = term.toLowerCase(Locale.ROOT);
            if (name.contains(normalized)) {
                score += 0.30;
            }
            if (filePath.contains(normalized)) {
                score += 0.10;
            }
            if (content.contains(normalized)) {
                score += 0.10;
            }
        }
        return score;
    }

    private String embeddingToJson(float[] embedding) throws IOException {
        ArrayNode array = mapper.createArrayNode();
        for (float value : embedding) {
            array.add(value);
        }
        return mapper.writeValueAsString(array);
    }

    private float[] jsonToEmbedding(String json) throws IOException {
        JsonNode root = mapper.readTree(json);
        float[] values = new float[root.size()];
        for (int i = 0; i < root.size(); i++) {
            values[i] = (float) root.get(i).asDouble();
        }
        return values;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        int length = Math.min(a.length, b.length);
        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private List<SearchResult> limit(List<SearchResult> candidates, int topK) {
        int size = Math.max(1, topK);
        return candidates.size() > size ? new ArrayList<>(candidates.subList(0, size)) : candidates;
    }

    public record SearchResult(
        String projectPath,
        String filePath,
        String chunkType,
        String name,
        String content,
        int startLine,
        int endLine,
        double similarity,
        double keywordScore,
        double finalScore
    ) {
        public String key() {
            return filePath + "#" + chunkType + "#" + name + "#" + startLine + "-" + endLine;
        }

        public SearchResult withScores(double similarity, double keywordScore, double finalScore) {
            return new SearchResult(
                projectPath, filePath, chunkType, name, content, startLine, endLine,
                similarity, keywordScore, finalScore
            );
        }
    }
}
