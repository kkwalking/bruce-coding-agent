package com.brucecli.rag.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.brucecli.rag.embedding.EmbeddingClient;
import com.brucecli.rag.search.CodeRetriever;
import com.brucecli.rag.search.SearchResultFormatter;
import com.brucecli.rag.store.VectorStore;
import com.brucecli.rag.store.VectorStore.SearchResult;
import com.brucecli.tool.Param;
import com.brucecli.tool.Tool;
import com.brucecli.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.List;

/**
 * 把 RAG 检索注册成 Agent 可调用的 search_code 工具。
 */
public class RagToolRegistrar {
    public static final String AGENT_INSTRUCTIONS = """
        你额外拥有 search_code 工具，用于检索当前项目源码。
        当用户询问项目结构、实现位置、调用链、类或方法职责时，优先调用 search_code，
        再基于检索结果回答；不要凭空猜测代码位置。
        """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RagToolRegistrar() {
    }

    public static void registerSearchCode(
        ToolRegistry toolRegistry,
        Path projectPath,
        EmbeddingClient embeddingClient,
        Path dbFile
    ) {
        String normalizedProjectPath = projectPath.toAbsolutePath().normalize().toString();
        toolRegistry.register(new Tool(
            "search_code",
            "语义检索代码库，根据自然语言描述查找相关代码块",
            createParameters(
                new Param("query", "string", "自然语言查询描述，例如'用户登录的实现'", true),
                new Param("top_k", "integer", "返回结果数量（默认5）", false)
            ),
            args -> {
                String query = args.get("query");
                int topK = parseTopK(args.get("top_k"));
                try (VectorStore vectorStore = new VectorStore(dbFile)) {
                    CodeRetriever retriever = new CodeRetriever(
                        normalizedProjectPath,
                        embeddingClient,
                        vectorStore
                    );
                    List<SearchResult> results = retriever.hybridSearch(query, topK);
                    return SearchResultFormatter.formatForTool(query, results);
                }
            }
        ));
    }

    private static JsonNode createParameters(Param... params) {
        ObjectNode parameters = MAPPER.createObjectNode();
        parameters.put("type", "object");
        ObjectNode properties = parameters.putObject("properties");
        ArrayNode required = parameters.putArray("required");

        for (Param param : params) {
            ObjectNode prop = properties.putObject(param.name());
            prop.put("type", param.type());
            prop.put("description", param.description());
            if (param.required()) {
                required.add(param.name());
            }
        }
        return parameters;
    }

    private static int parseTopK(String raw) {
        if (raw == null || raw.isBlank()) {
            return 5;
        }
        try {
            return Math.max(1, Integer.parseInt(raw.replace("\"", "")));
        } catch (NumberFormatException ignored) {
            return 5;
        }
    }
}
