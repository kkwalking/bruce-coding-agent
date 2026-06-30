package com.brucecli.web.search;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZhipuSearchProviderTest {
    @Test
    void postsToZhipuEndpointWithGlmApiKeyAndParsesResults() throws Exception {
        AtomicReference<Request> capturedRequest = new AtomicReference<>();
        OkHttpClient client = new OkHttpClient.Builder()
            .addInterceptor(chain -> {
                capturedRequest.set(chain.request());
                String body = """
                    {
                      "search_result": [
                        {
                          "title": "Bruce Coding Agent 发布",
                          "link": "https://example.com/bruce",
                          "content": "bruce 增加联网搜索能力",
                          "media": "Example",
                          "publish_date": "2026-06-21"
                        }
                      ]
                    }
                    """;
                return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(body, MediaType.parse("application/json")))
                    .build();
            })
            .build();
        WebSearchConfig config = new WebSearchConfig(
            "",
            "glm-test-key",
            "search_std",
            "medium",
            "",
            "",
            "https://open.bigmodel.cn/api/paas/v4/web_search"
        );

        List<WebSearchResult> results = new ZhipuSearchProvider(config, client).search("Bruce Coding Agent 最新消息", 3);

        Request request = capturedRequest.get();
        assertEquals("https://open.bigmodel.cn/api/paas/v4/web_search", request.url().toString());
        assertEquals("Bearer glm-test-key", request.header("Authorization"));
        Buffer buffer = new Buffer();
        request.body().writeTo(buffer);
        String requestBody = buffer.readUtf8();
        assertTrue(requestBody.contains("\"search_engine\":\"search_std\""));
        assertTrue(requestBody.contains("\"content_size\":\"medium\""));

        assertEquals(1, results.size());
        assertEquals("Bruce Coding Agent 发布", results.get(0).title());
        assertEquals("https://example.com/bruce", results.get(0).url());
        assertEquals("Example", results.get(0).source());
    }
}
