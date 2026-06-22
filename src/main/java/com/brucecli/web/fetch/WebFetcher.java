package com.brucecli.web.fetch;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 抓取 URL 并提取正文。
 */
public class WebFetcher {
    private static final int DEFAULT_MAX_CHARS = 8_000;
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
    private static final int BUFFER_SIZE = 8 * 1024;

    private final OkHttpClient httpClient;
    private final NetworkPolicy networkPolicy;
    private final HtmlExtractor htmlExtractor;

    public WebFetcher() {
        this(new OkHttpClient.Builder()
            .callTimeout(Duration.ofSeconds(30))
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
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("响应体为空");
            }
            Charset charset = resolveCharset(response, body);
            BoundedBytes responseBody = readBounded(body.byteStream());
            String responseText = new String(responseBody.bytes(), charset);
            if (!response.isSuccessful()) {
                throw new IOException("WebFetch 请求失败: HTTP " + response.code() + "\n" + truncate(responseText, 2_000).text());
            }

            String finalUrl = response.request().url().toString();
            ExtractedContent extracted = htmlExtractor.extract(finalUrl, responseText);
            Truncated content = truncate(extracted.markdown(), normalizeMaxChars(maxChars));
            String contentType = response.header("Content-Type", "");
            return new FetchedPage(
                rawUrl,
                finalUrl,
                response.code(),
                contentType,
                extracted.title(),
                content.text(),
                responseBody.truncated() || content.truncated()
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

    private BoundedBytes readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(MAX_RESPONSE_BYTES, 64 * 1024));
        byte[] buffer = new byte[BUFFER_SIZE];
        int total = 0;
        while (total < MAX_RESPONSE_BYTES) {
            int maxRead = Math.min(buffer.length, MAX_RESPONSE_BYTES - total);
            int read = input.read(buffer, 0, maxRead);
            if (read == -1) {
                return new BoundedBytes(output.toByteArray(), false);
            }
            output.write(buffer, 0, read);
            total += read;
        }
        return new BoundedBytes(output.toByteArray(), true);
    }

    private Charset resolveCharset(Response response, ResponseBody body) {
        try {
            MediaType contentType = body.contentType();
            if (contentType != null && contentType.charset() != null) {
                return contentType.charset();
            }
        } catch (Exception ignored) {
            // Content-Type 里的 charset 可能不合法，解不出来时统一回退 UTF-8。
        }
        try {
            String header = response.header("Content-Type", "");
            MediaType contentType = MediaType.parse(header);
            if (contentType != null && contentType.charset() != null) {
                return contentType.charset();
            }
        } catch (Exception ignored) {
            // 同上，header 解析失败不影响抓取流程。
        }
        return StandardCharsets.UTF_8;
    }

    private record Truncated(String text, boolean truncated) {
    }

    private record BoundedBytes(byte[] bytes, boolean truncated) {
    }
}
