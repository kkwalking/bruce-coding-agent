package com.brucecli.agent.multi.model;

import java.util.Locale;

/**
 * Planner 输出的步骤类型。
 *
 * <p>类型不会直接决定执行逻辑；它主要帮助 Worker 理解这一步偏向文件读取、
 * 文件写入、命令执行、验证还是通用分析。</p>
 */
public enum StepType {
    ANALYSIS,
    FILE_READ,
    FILE_WRITE,
    COMMAND,
    VERIFICATION,
    GENERAL;

    public static StepType from(String raw) {
        if (raw == null || raw.isBlank()) {
            return GENERAL;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_');
        try {
            return StepType.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return GENERAL;
        }
    }
}
