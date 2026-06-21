package com.brucecli.web.search;

import java.util.List;

public class WebSearchFormatter {
    private WebSearchFormatter() {
    }

    public static String format(String query, String providerName, List<WebSearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "没有搜索到相关结果: " + query;
        }

        StringBuilder builder = new StringBuilder();
        builder.append("联网搜索结果")
            .append(" (provider=").append(providerName).append(")")
            .append(": ").append(query).append("\n");

        int index = 1;
        for (WebSearchResult result : results) {
            builder.append("\n").append(index++).append(". ").append(result.safeTitle()).append("\n");
            if (!result.safeUrl().isBlank()) {
                builder.append("   URL: ").append(result.safeUrl()).append("\n");
            }
            String sourceLine = joinNonBlank(result.safeSource(), result.safePublishedAt());
            if (!sourceLine.isBlank()) {
                builder.append("   来源: ").append(sourceLine).append("\n");
            }
            if (!result.safeSnippet().isBlank()) {
                builder.append("   摘要: ").append(result.safeSnippet()).append("\n");
            }
        }
        return builder.toString().trim();
    }

    private static String joinNonBlank(String left, String right) {
        if (left == null || left.isBlank()) {
            return right == null ? "" : right;
        }
        if (right == null || right.isBlank()) {
            return left;
        }
        return left + " / " + right;
    }
}
