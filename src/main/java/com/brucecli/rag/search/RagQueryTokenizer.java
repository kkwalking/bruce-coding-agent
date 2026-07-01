package com.brucecli.rag.search;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * RAG 查询分词器。
 *
 * <p>同时照顾中文自然语言和 Java 标识符：jieba 负责中文，ASCII 扫描负责
 * Agent、LoginService、run 这类代码关键词。</p>
 */
public class RagQueryTokenizer {
    private static final Set<String> STOP_WORDS = Set.of(
        "怎么", "如何", "一下", "一个", "这个", "那个", "哪里", "哪些",
        "什么", "为啥", "为什么", "实现", "代码", "项目", "地方"
    );

    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    public Set<String> tokenize(String query) {
        Set<String> terms = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return terms;
        }

        for (SegToken token : segmenter.process(query, JiebaSegmenter.SegMode.INDEX)) {
            addTerm(terms, token.word);
        }
        addAsciiTerms(terms, query);
        return terms;
    }

    private void addAsciiTerms(Set<String> terms, String text) {
        StringBuilder current = new StringBuilder();
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            if ((Character.isLetterOrDigit(codePoint) || codePoint == '_' || codePoint == '.') && codePoint < 128) {
                current.appendCodePoint(Character.toLowerCase(codePoint));
            } else {
                flushAscii(terms, current);
            }
            offset += Character.charCount(codePoint);
        }
        flushAscii(terms, current);
    }

    private void flushAscii(Set<String> terms, StringBuilder current) {
        if (current.length() > 1) {
            addTerm(terms, current.toString());
            String[] parts = current.toString().split("[._]");
            for (String part : parts) {
                addTerm(terms, part);
            }
        }
        current.setLength(0);
    }

    private void addTerm(Set<String> terms, String rawTerm) {
        if (rawTerm == null) {
            return;
        }
        String term = rawTerm.trim().toLowerCase(Locale.ROOT);
        if (term.length() <= 1 || STOP_WORDS.contains(term)) {
            return;
        }
        terms.add(term);
    }
}
