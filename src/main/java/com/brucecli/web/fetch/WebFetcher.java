package com.brucecli.web.fetch;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 抓取 URL 并提取正文。
 */
public class WebFetcher {
    private static final int DEFAULT_MAX_CHARS = 8_000;
    private static final int MAX_HTML_CHARS = 1_000_000;

    private final OkHttpClient httpClient;
    private final NetworkPolicy networkPolicy;
    private final HtmlExtractor htmlExtractor;

    public WebFetcher() {
        this(new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(20))
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build(), new NetworkPolicy(), new HtmlExtractor());
    }

    WebFetcher(OkHttpClient httpClient, NetworkPolicy networkPolicy, HtmlExtractor htmlExtractor) {
        this.httpClient = httpClient;
        this.networkPolicy = networkPolicy;
        this.htmlExtractor = htmlExtractor;
    }

    public FetchedPage fetch(String rawUrl, int maxChars) throws IOException {
        HttpUrl url = networkPolicy.validateUrl(rawUrl);
        networkPolicy.acquire();

        Request request = new Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (compatible; bruce-cli-web-fetch/1.0)")
            .get()
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("WebFetch 请求失败: HTTP " + response.code() + "\n" + truncate(responseBody, 2_000).text());
            }

            String html = truncate(responseBody, MAX_HTML_CHARS).text();
            ExtractedContent extracted = htmlExtractor.extract(url.toString(), html);
            Truncated content = truncate(extracted.markdown(), normalizeMaxChars(maxChars));
            String contentType = response.header("Content-Type", "");
            return new FetchedPage(
                rawUrl,
                response.request().url().toString(),
                response.code(),
                contentType,
                extracted.title(),
                content.text(),
                content.truncated()
            );
        }
    }

    public FetchedPage fetch(String rawUrl) throws IOException {
        return fetch(rawUrl, DEFAULT_MAX_CHARS);
    }

    private int normalizeMaxChars(int maxChars) {
        if (maxChars <= 0) {
            return DEFAULT_MAX_CHARS;
        }
        return Math.max(500, Math.min(50_000, maxChars));
    }

    private Truncated truncate(String value, int limit) {
        String text = value == null ? "" : value;
        if (text.length() <= limit) {
            return new Truncated(text, false);
        }
        return new Truncated(text.substring(0, limit) + "\n... 内容过长，已截断 ...", true);
    }

    private record Truncated(String text, boolean truncated) {
    }
}
