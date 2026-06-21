package com.brucecli.rag.chunk;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.brucecli.rag.model.CodeChunk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * AST 代码分块器。
 *
 * <p>Java 文件按类和方法切；非 Java 文件按字符大小切。JavaParser 解析失败时
 * 自动回退到普通文本分块，避免一个语法错误文件拖垮整个索引流程。</p>
 */
public class CodeChunker {
    public static final int MAX_CHUNK_CHARS = 2_000;
    private static final int CLASS_HEADER_LINES = 5;

    private final Path projectRoot;
    private final JavaParser javaParser;

    public CodeChunker(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        ParserConfiguration configuration = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        this.javaParser = new JavaParser(configuration);
    }

    public List<CodeChunk> chunkFile(Path filePath) throws IOException {
        Path absolutePath = filePath.toAbsolutePath().normalize();
        String relativePath = toRelativePath(absolutePath);
        String content = Files.readString(absolutePath, StandardCharsets.UTF_8);

        if (!relativePath.endsWith(".java")) {
            return chunkLargeText(relativePath, content);
        }

        try {
            return chunkJavaFile(relativePath, content);
        } catch (RuntimeException e) {
            return chunkLargeText(relativePath, content);
        }
    }

    private List<CodeChunk> chunkJavaFile(String relativePath, String content) {
        CompilationUnit cu = javaParser.parse(content)
            .getResult()
            .orElseThrow(() -> new IllegalArgumentException("JavaParser 没有返回 AST"));

        List<CodeChunk> chunks = new ArrayList<>();
        String[] lines = content.split("\\R", -1);
        for (ClassOrInterfaceDeclaration clazz : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String className = clazz.getNameAsString();
            Range classRange = clazz.getRange().orElse(null);
            if (classRange != null) {
                int classStart = classRange.begin.line;
                int classEnd = Math.min(classRange.end.line, classStart + CLASS_HEADER_LINES - 1);
                String classHeader = sliceLines(lines, classStart, classEnd);
                chunks.add(CodeChunk.classChunk(projectPath(), relativePath, className, classHeader, classStart, classEnd));
            }

            for (MethodDeclaration method : clazz.getMethods()) {
                Range methodRange = method.getRange().orElse(null);
                if (methodRange == null) {
                    continue;
                }
                String signature = method.getDeclarationAsString(false, false, true);
                String methodName = className + "." + signature;
                String methodContent = sliceLines(lines, methodRange.begin.line, methodRange.end.line);
                chunks.add(CodeChunk.methodChunk(
                    projectPath(),
                    relativePath,
                    methodName,
                    methodContent,
                    methodRange.begin.line,
                    methodRange.end.line
                ));
            }
        }

        if (chunks.isEmpty()) {
            return chunkLargeText(relativePath, content);
        }
        return chunks;
    }

    private List<CodeChunk> chunkLargeText(String relativePath, String content) {
        List<CodeChunk> chunks = new ArrayList<>();
        String[] lines = content.split("\\R", -1);
        int startOffset = 0;
        while (startOffset < content.length()) {
            int endOffset = Math.min(content.length(), startOffset + MAX_CHUNK_CHARS);
            String chunk = content.substring(startOffset, endOffset);
            int startLine = lineNumberAt(content, startOffset);
            int endLine = lineNumberAt(content, Math.max(startOffset, endOffset - 1));
            chunks.add(CodeChunk.fileChunk(projectPath(), relativePath, chunk, startLine, endLine));
            startOffset = endOffset;
        }
        if (content.isEmpty()) {
            chunks.add(CodeChunk.fileChunk(projectPath(), relativePath, "", 1, Math.max(1, lines.length)));
        }
        return chunks;
    }

    private String sliceLines(String[] lines, int startLine, int endLine) {
        int start = Math.max(1, startLine);
        int end = Math.min(lines.length, endLine);
        StringBuilder builder = new StringBuilder();
        for (int i = start; i <= end; i++) {
            builder.append(lines[i - 1]);
            if (i < end) {
                builder.append('\n');
            }
        }
        return builder.toString();
    }

    private int lineNumberAt(String content, int offset) {
        int line = 1;
        for (int i = 0; i < Math.min(offset, content.length()); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private String toRelativePath(Path absolutePath) {
        if (absolutePath.startsWith(projectRoot)) {
            return projectRoot.relativize(absolutePath).toString();
        }
        return absolutePath.toString();
    }

    private String projectPath() {
        return projectRoot.toString();
    }
}
