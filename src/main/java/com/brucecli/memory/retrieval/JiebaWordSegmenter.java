package com.brucecli.memory.retrieval;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 基于 jieba-analysis 的中文分词器。
 *
 * <p>使用 {@link JiebaSegmenter.SegMode#INDEX} 模式，原因是记忆检索更像搜索索引：
 * 我们希望同一句话能切出更丰富的候选词，提高“用户偏好”“项目构建”等短查询的召回率。</p>
 */
public class JiebaWordSegmenter {
    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    public Set<String> segment(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return terms;
        }

        for (SegToken token : segmenter.process(text, JiebaSegmenter.SegMode.INDEX)) {
            addTerm(terms, token.word);
        }

        // jieba 对中英文混合场景能切出英文 token，但这里额外扫一遍 ASCII，
        // 可以保证 JDK、Maven、React、文件名这类工程词稳定进入索引。
        addAsciiTerms(terms, text);
        return terms;
    }

    private void addAsciiTerms(Set<String> terms, String text) {
        StringBuilder current = new StringBuilder();
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            if (Character.isLetterOrDigit(codePoint) && codePoint < 128) {
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
            terms.add(current.toString());
        }
        current.setLength(0);
    }

    private void addTerm(Set<String> terms, String rawTerm) {
        if (rawTerm == null) {
            return;
        }
        String term = rawTerm.trim().toLowerCase(Locale.ROOT);
        if (term.length() > 1) {
            terms.add(term);
        }
    }
}
