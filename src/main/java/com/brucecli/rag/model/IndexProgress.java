package com.brucecli.rag.model;

/**
 * RAG 索引过程中的轻量进度快照。
 */
public record IndexProgress(
    String project,
    int processedFiles,
    int totalFiles,
    int chunks,
    int relations,
    String currentFile,
    int warningCount,
    String phase
) {
    public IndexProgress {
        project = project == null ? "" : project;
        processedFiles = Math.max(0, processedFiles);
        totalFiles = Math.max(0, totalFiles);
        chunks = Math.max(0, chunks);
        relations = Math.max(0, relations);
        currentFile = currentFile == null ? "" : currentFile;
        warningCount = Math.max(0, warningCount);
        phase = phase == null || phase.isBlank() ? "indexing" : phase;
    }
}
