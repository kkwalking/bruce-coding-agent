package com.brucecli.web.fetch;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;
import org.jsoup.select.Elements;

import java.util.Locale;

/**
 * 从 HTML 中提取更适合 LLM 阅读的正文 Markdown。
 */
public class HtmlExtractor {
    private static final String SEMANTIC_SELECTORS = String.join(",",
        "article",
        "main",
        "[role=main]",
        ".article-content",
        ".markdown-body",
        ".post-content",
        ".entry-content",
        ".content"
    );

    public ExtractedContent extract(String url, String html) {
        if (html == null || html.isBlank()) {
            return new ExtractedContent("", "");
        }

        Document document = Jsoup.parse(html, url == null ? "" : url);
        document.select("script,style,noscript,iframe,svg,canvas,nav,header,footer,aside,form,button").remove();

        Element content = chooseContent(document);
        String markdown = renderMarkdown(content).trim();
        if (markdown.isBlank()) {
            markdown = Jsoup.clean(content.text(), Safelist.none()).trim();
        }
        return new ExtractedContent(document.title(), collapseBlankLines(markdown));
    }

    private Element chooseContent(Document document) {
        Element best = null;
        double bestScore = 0.0;

        Elements semantic = document.select(SEMANTIC_SELECTORS);
        for (Element element : semantic) {
            double score = score(element) * 1.2;
            if (score > bestScore) {
                best = element;
                bestScore = score;
            }
        }

        if (best != null && best.text().length() >= 80) {
            return best;
        }

        for (Element element : document.body().select("article,main,section,div")) {
            double score = score(element);
            if (score > bestScore) {
                best = element;
                bestScore = score;
            }
        }
        return best == null ? document.body() : best;
    }

    private double score(Element element) {
        String text = element.text();
        int textLength = text == null ? 0 : text.length();
        if (textLength < 80) {
            return 0.0;
        }

        int linkLength = 0;
        for (Element link : element.select("a")) {
            linkLength += link.text().length();
        }
        double linkRatio = textLength == 0 ? 0.0 : (double) linkLength / textLength;
        double penalty = Math.min(linkRatio * 2.0, 1.0);
        return textLength * (1.0 - penalty);
    }

    private String renderMarkdown(Element root) {
        StringBuilder builder = new StringBuilder();
        renderNode(root, builder);
        return builder.toString();
    }

    private void renderNode(Node node, StringBuilder builder) {
        if (node instanceof TextNode textNode) {
            appendInline(builder, textNode.text());
            return;
        }
        if (!(node instanceof Element element)) {
            for (Node child : node.childNodes()) {
                renderNode(child, builder);
            }
            return;
        }

        String tag = element.tagName().toLowerCase(Locale.ROOT);
        switch (tag) {
            case "h1", "h2", "h3" -> appendBlock(builder, "#".repeat(headingLevel(tag)) + " " + element.text());
            case "h4", "h5", "h6" -> appendBlock(builder, "#### " + element.text());
            case "p" -> appendBlock(builder, element.text());
            case "li" -> appendBlock(builder, "- " + element.text());
            case "pre" -> appendBlock(builder, "```\n" + element.text() + "\n```");
            case "blockquote" -> appendBlock(builder, "> " + element.text());
            case "br" -> builder.append("\n");
            default -> {
                if (element.children().isEmpty() && !element.ownText().isBlank()) {
                    appendInline(builder, element.ownText());
                }
                for (Node child : element.childNodes()) {
                    renderNode(child, builder);
                }
                if (isBlock(tag)) {
                    ensureBlankLine(builder);
                }
            }
        }
    }

    private int headingLevel(String tag) {
        return Integer.parseInt(tag.substring(1));
    }

    private boolean isBlock(String tag) {
        return switch (tag) {
            case "div", "section", "article", "main", "ul", "ol", "table" -> true;
            default -> false;
        };
    }

    private void appendInline(StringBuilder builder, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!builder.isEmpty() && !Character.isWhitespace(builder.charAt(builder.length() - 1))) {
            builder.append(' ');
        }
        builder.append(text.trim());
    }

    private void appendBlock(StringBuilder builder, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        ensureBlankLine(builder);
        builder.append(text.trim()).append("\n\n");
    }

    private void ensureBlankLine(StringBuilder builder) {
        if (builder.isEmpty()) {
            return;
        }
        int length = builder.length();
        if (length >= 2 && builder.charAt(length - 1) == '\n' && builder.charAt(length - 2) == '\n') {
            return;
        }
        if (builder.charAt(length - 1) != '\n') {
            builder.append('\n');
        }
        builder.append('\n');
    }

    private String collapseBlankLines(String text) {
        return text.replaceAll("[ \\t]+\\n", "\n")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }
}
