package com.brucecli.rag.model;

/**
 * 代码关系图谱的一条边。
 */
public record CodeRelation(
    String projectPath,
    String fromFilePath,
    String fromName,
    String toFilePath,
    String toName,
    String relationType
) {
    public CodeRelation {
        projectPath = projectPath == null ? "" : projectPath;
        fromFilePath = fromFilePath == null ? "" : fromFilePath;
        fromName = fromName == null ? "" : fromName;
        toFilePath = toFilePath == null ? "" : toFilePath;
        toName = toName == null ? "" : toName;
        relationType = relationType == null ? "" : relationType;
    }

    public static CodeRelation of(
        String projectPath,
        String fromFilePath,
        String fromName,
        String toFilePath,
        String toName,
        String relationType
    ) {
        return new CodeRelation(projectPath, fromFilePath, fromName, toFilePath, toName, relationType);
    }
}
