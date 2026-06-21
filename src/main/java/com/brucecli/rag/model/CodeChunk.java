package com.brucecli.rag.model;

/**
 * 代码块数据模型。
 *
 * <p>RAG 不直接把整个文件喂给模型，而是按文件、类、方法等粒度切成 chunk。
 * chunk 里保留路径、类型、名称和行号，检索结果才能定位到具体代码位置。</p>
 */
public record CodeChunk(
    String projectPath,
    String filePath,
    String chunkType,
    String name,
    String content,
    int startLine,
    int endLine
) {
    public CodeChunk {
        projectPath = projectPath == null ? "" : projectPath;
        filePath = filePath == null ? "" : filePath;
        chunkType = chunkType == null || chunkType.isBlank() ? "file" : chunkType;
        name = name == null || name.isBlank() ? filePath : name;
        content = content == null ? "" : content;
    }

    public static CodeChunk fileChunk(String projectPath, String filePath, String content, int startLine, int endLine) {
        return new CodeChunk(projectPath, filePath, "file", filePath, content, startLine, endLine);
    }

    public static CodeChunk classChunk(
        String projectPath,
        String filePath,
        String className,
        String content,
        int startLine,
        int endLine
    ) {
        return new CodeChunk(projectPath, filePath, "class", className, content, startLine, endLine);
    }

    public static CodeChunk methodChunk(
        String projectPath,
        String filePath,
        String methodName,
        String content,
        int startLine,
        int endLine
    ) {
        return new CodeChunk(projectPath, filePath, "method", methodName, content, startLine, endLine);
    }

    /**
     * 送入 Embedding 的文本刻意带上结构前缀，让向量模型知道这是哪个类/方法。
     */
    public String toEmbeddingText() {
        return "[%s:%s] %s".formatted(chunkType, name, content);
    }
}
