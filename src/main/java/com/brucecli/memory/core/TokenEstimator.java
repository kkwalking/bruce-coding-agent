package com.brucecli.memory.core;

/**
 * Token 估算器。
 *
 * <p>文章里的估算策略是：中文约 1.5 字一个 token，英文约 4 个字符一个 token。
 * 精确实现需要接具体模型 tokenizer；学习版先用这个粗略估算支撑预算控制。</p>
 */
public final class TokenEstimator {
    private TokenEstimator() {
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long chineseChars = text.chars()
            .filter(c -> c > 0x4E00 && c < 0x9FFF)
            .count();
        long otherChars = text.length() - chineseChars;
        return (int) Math.ceil(chineseChars / 1.5 + otherChars / 4.0);
    }
}
