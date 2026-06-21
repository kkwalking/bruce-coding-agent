package com.brucecli.web.search;

/**
 * 搜索 Provider 返回给 Agent 的统一结果结构。
 */
public record WebSearchResult(
    String title,
    String url,
    String snippet,
    String source,
    String publishedAt
) {
    public String safeTitle() {
        return isBlank(title) ? "无标题" : title.trim();
    }

    public String safeUrl() {
        return isBlank(url) ? "" : url.trim();
    }

    public String safeSnippet() {
        return isBlank(snippet) ? "" : snippet.trim();
    }

    public String safeSource() {
        return isBlank(source) ? "" : source.trim();
    }

    public String safePublishedAt() {
        return isBlank(publishedAt) ? "" : publishedAt.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
