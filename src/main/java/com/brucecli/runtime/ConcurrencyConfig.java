package com.brucecli.runtime;

import java.time.Duration;

/**
 * 并发执行统一配置。
 */
public record ConcurrencyConfig(
    int maxParallelism,
    Duration batchTimeout,
    int maxOutputChars
) {
    public static ConcurrencyConfig defaults() {
        return new ConcurrencyConfig(
            Math.max(2, Runtime.getRuntime().availableProcessors()),
            Duration.ofSeconds(30),
            8_000
        );
    }

    public ConcurrencyConfig {
        if (maxParallelism <= 0) {
            throw new IllegalArgumentException("maxParallelism 必须大于 0");
        }
        if (batchTimeout == null || batchTimeout.isZero() || batchTimeout.isNegative()) {
            throw new IllegalArgumentException("batchTimeout 必须为正数");
        }
        if (maxOutputChars <= 0) {
            throw new IllegalArgumentException("maxOutputChars 必须大于 0");
        }
    }

    public int parallelismFor(int taskCount) {
        if (taskCount <= 0) {
            return 1;
        }
        return Math.max(1, Math.min(maxParallelism, taskCount));
    }

    public String truncate(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxOutputChars) {
            return text;
        }
        return text.substring(0, maxOutputChars) + "\n... 输出过长，已截断 ...";
    }
}
