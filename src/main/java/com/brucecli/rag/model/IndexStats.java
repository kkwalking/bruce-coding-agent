package com.brucecli.rag.model;

/**
 * 一次索引任务的统计结果。
 */
public record IndexStats(
    String projectPath,
    int files,
    int chunks,
    int relations,
    long durationMillis
) {
    public String toDisplayString() {
        return "索引完成: files=%d, chunks=%d, relations=%d, duration=%dms, project=%s"
            .formatted(files, chunks, relations, durationMillis, projectPath);
    }
}
