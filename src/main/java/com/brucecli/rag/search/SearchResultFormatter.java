package com.brucecli.rag.search;

import com.brucecli.rag.model.CodeRelation;
import com.brucecli.rag.store.VectorStore.SearchResult;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 检索结果格式化。
 */
public class SearchResultFormatter {
    private static final int TOOL_SNIPPET_CHARS = 900;
    private static final int CLI_SNIPPET_CHARS = 500;

    public static String formatForCli(String query, List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "没有检索到相关代码。请先确认已执行 /index，或换个查询描述。";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("🔎 查询: ").append(query).append('\n');
        builder.append("📋 找到 ").append(results.size()).append(" 条结果:\n\n");
        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            builder.append(i + 1)
                .append(". ")
                .append(result.name())
                .append(" [").append(result.chunkType()).append("] ")
                .append(result.filePath())
                .append(":").append(result.startLine()).append("-").append(result.endLine())
                .append(" score=").append(String.format("%.3f", result.finalScore()))
                .append('\n')
                .append(indent(abbreviate(result.content(), CLI_SNIPPET_CHARS)))
                .append("\n\n");
        }
        return builder.toString();
    }

    public static String formatForTool(String query, List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "代码库尚未索引或没有找到相关代码。请先使用 /index 命令建立索引。";
        }

        StringBuilder builder = new StringBuilder();
        builder.append("代码检索结果摘要\n");
        builder.append("- 查询: ").append(query).append('\n');
        builder.append("- 最相关入口: ").append(results.get(0).name())
            .append(" (").append(results.get(0).filePath()).append(")\n");
        builder.append("- 主要文件: ").append(topFiles(results)).append('\n');
        builder.append("- 排序综合参考: 语义相似度、关键词命中、chunk 类型加分、双重命中奖励\n\n");

        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            builder.append("## Result ").append(i + 1).append('\n');
            builder.append("name: ").append(result.name()).append('\n');
            builder.append("type: ").append(result.chunkType()).append('\n');
            builder.append("file: ").append(result.filePath())
                .append(":").append(result.startLine()).append("-").append(result.endLine()).append('\n');
            builder.append("score: ").append(String.format("%.3f", result.finalScore())).append('\n');
            builder.append("```java\n")
                .append(abbreviate(result.content(), TOOL_SNIPPET_CHARS))
                .append("\n```\n\n");
        }
        return builder.toString();
    }

    public static String formatGraph(String name, List<CodeRelation> relations) {
        if (relations == null || relations.isEmpty()) {
            return "没有找到关系图谱: " + name;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("🕸️ 查询类关系图谱: ").append(name).append('\n');
        builder.append("📋 找到 ").append(relations.size()).append(" 条关系:\n\n");
        for (CodeRelation relation : relations) {
            builder.append("   ")
                .append(relation.fromName())
                .append(" --").append(relation.relationType()).append("--> ")
                .append(relation.toName())
                .append('\n');
        }
        return builder.toString();
    }

    private static String topFiles(List<SearchResult> results) {
        Map<String, Long> counts = results.stream()
            .collect(Collectors.groupingBy(SearchResult::filePath, Collectors.counting()));
        return counts.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
            .limit(3)
            .map(Map.Entry::getKey)
            .collect(Collectors.joining(", "));
    }

    private static String indent(String text) {
        return text.lines().map(line -> "   " + line).collect(Collectors.joining("\n"));
    }

    private static String abbreviate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars) + "\n... 截断 ...";
    }
}
