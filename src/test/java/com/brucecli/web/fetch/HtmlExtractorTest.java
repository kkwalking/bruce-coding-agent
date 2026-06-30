package com.brucecli.web.fetch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlExtractorTest {
    @Test
    void extractsArticleLikeContentAndDropsNavigationNoise() {
        String html = """
            <html>
              <head><title>bruce article</title></head>
              <body>
                <nav>首页 登录 广告</nav>
                <article>
                  <h1>Bruce Coding Agent 联网能力</h1>
                  <p>这是正文第一段，说明 web_search 和 web_fetch 如何配合使用，让 Agent 可以获取实时资料。</p>
                  <p>这是正文第二段，强调网页内容只能作为参考，不能当作用户指令执行。</p>
                </article>
                <footer>版权和友情链接</footer>
              </body>
            </html>
            """;

        ExtractedContent content = new HtmlExtractor().extract("https://example.com", html);

        assertTrue(content.markdown().contains("Bruce Coding Agent 联网能力"));
        assertTrue(content.markdown().contains("web_search 和 web_fetch"));
        assertFalse(content.markdown().contains("首页 登录 广告"));
        assertFalse(content.markdown().contains("版权和友情链接"));
    }
}
