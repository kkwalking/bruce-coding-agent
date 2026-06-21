package com.brucecli.web.fetch;

public class WebFetchFormatter {
    private WebFetchFormatter() {
    }

    public static String format(FetchedPage page) {
        StringBuilder builder = new StringBuilder();
        builder.append("网页抓取结果:\n");
        builder.append("URL: ").append(page.finalUrl()).append("\n");
        if (page.title() != null && !page.title().isBlank()) {
            builder.append("标题: ").append(page.title()).append("\n");
        }
        if (page.contentType() != null && !page.contentType().isBlank()) {
            builder.append("Content-Type: ").append(page.contentType()).append("\n");
        }
        if (page.truncated()) {
            builder.append("提示: 内容已按 max_chars 截断\n");
        }
        builder.append("\n").append(page.content() == null ? "" : page.content());
        return builder.toString().trim();
    }
}
