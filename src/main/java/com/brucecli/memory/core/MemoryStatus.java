package com.brucecli.memory.core;

/**
 * Memory 系统运行状态快照。
 *
 * <p>这个对象主要服务 /status 命令，用于观察当前上下文是否接近预算、
 * 是否发生过压缩、最近一次注入给 LLM 的 Memory prompt 大概占多少 token。</p>
 */
public record MemoryStatus(
    int shortTermEntries,
    int shortTermTokens,
    int shortTermMaxTokens,
    double shortTermUsageRatio,
    int pendingCompressionEntries,
    int compressedSummaryEntries,
    int longTermEntries,
    int contextBuildCount,
    int lastContextTokens,
    int lastRelevantLongTermEntries,
    int lastSummaryEntries,
    int lastRecentShortTermEntries,
    int compressionCount,
    int compressedSourceEntries,
    boolean shouldCompress
) {
    public String toMarkdown() {
        return """
            # Memory Status

            - 短期记忆: %d 条，%d / %d tokens，使用率 %.1f%%
            - 长期记忆: %d 条
            - 待压缩旧消息: %d 条
            - 已生成摘要: %d 条
            - 上下文构建次数: %d 次
            - 最近一次 Memory prompt: %d tokens
            - 最近一次注入: 长期记忆 %d 条，摘要 %d 条，短期记忆 %d 条
            - 压缩次数: %d 次，累计压缩源消息 %d 条
            - 当前是否达到压缩条件: %s
            """.formatted(
            shortTermEntries,
            shortTermTokens,
            shortTermMaxTokens,
            shortTermUsageRatio * 100,
            longTermEntries,
            pendingCompressionEntries,
            compressedSummaryEntries,
            contextBuildCount,
            lastContextTokens,
            lastRelevantLongTermEntries,
            lastSummaryEntries,
            lastRecentShortTermEntries,
            compressionCount,
            compressedSourceEntries,
            shouldCompress ? "是" : "否"
        );
    }
}
