package com.brucecli.web.fetch;

public record FetchedPage(
    String requestedUrl,
    String finalUrl,
    int statusCode,
    String contentType,
    String title,
    String content,
    boolean truncated
) {
}
