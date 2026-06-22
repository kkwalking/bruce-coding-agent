package com.brucecli.web.fetch;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebFetcherTest {
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;

    @Test
    void decodesResponseUsingContentTypeCharset() throws Exception {
        String html = """
            <html>
              <head><title>charset test</title></head>
              <body>
                <article>
                  <p>café déjà vu：这段正文用于验证 WebFetcher 会优先按 Content-Type charset 解码，而不是总是使用 UTF-8。</p>
                </article>
              </body>
            </html>
            """;
        WebFetcher fetcher = new WebFetcher(
            httpClient(html.getBytes(StandardCharsets.ISO_8859_1), "text/html; charset=ISO-8859-1"),
            new PermissiveNetworkPolicy(),
            new HtmlExtractor()
        );

        FetchedPage page = fetcher.fetch("http://example.test/article", 2_000);

        assertTrue(page.content().contains("café déjà vu"));
        assertEquals("text/html; charset=ISO-8859-1", page.contentType());
    }

    @Test
    void readsAtMostFiveMegabytesBeforeExtractingHtml() throws Exception {
        byte[] body = new byte[MAX_RESPONSE_BYTES + 4_096];
        Arrays.fill(body, (byte) 'a');
        CapturingExtractor extractor = new CapturingExtractor();
        WebFetcher fetcher = new WebFetcher(
            httpClient(body, "text/html; charset=UTF-8"),
            new PermissiveNetworkPolicy(),
            extractor
        );

        FetchedPage page = fetcher.fetch("http://example.test/large", 50_000);

        assertEquals(MAX_RESPONSE_BYTES, extractor.capturedHtml.length());
        assertTrue(page.truncated());
    }

    private OkHttpClient httpClient(byte[] body, String contentType) {
        return new OkHttpClient.Builder()
            .addInterceptor(chain -> new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .header("Content-Type", contentType)
                .body(ResponseBody.create(body, MediaType.parse(contentType)))
                .build())
            .build();
    }

    private static class CapturingExtractor extends HtmlExtractor {
        private String capturedHtml = "";

        @Override
        public ExtractedContent extract(String url, String html) {
            capturedHtml = html;
            return new ExtractedContent("large", html);
        }
    }

    private static class PermissiveNetworkPolicy extends NetworkPolicy {
        private PermissiveNetworkPolicy() {
            super(100, Duration.ofMinutes(1), Clock.systemUTC());
        }

        @Override
        public HttpUrl validateUrl(String rawUrl) {
            return HttpUrl.parse(rawUrl);
        }

        @Override
        public synchronized void acquire() {
        }
    }
}
