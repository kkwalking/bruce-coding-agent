package com.brucecli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {
    @TempDir
    Path tempDir;

    @Test
    void writesAndReadsFileInsideWorkspace() throws Exception {
        ToolRegistry registry = new ToolRegistry(tempDir);

        // 先用 write_file 写入，再用 read_file 读取，覆盖最基础的文件工具闭环。
        String writeResult = registry.executeTool("write_file", Map.of(
            "path", "notes/hello.txt",
            "content", "Hello BruceCLI"
        ));
        String readResult = registry.executeTool("read_file", Map.of("path", "notes/hello.txt"));

        assertTrue(writeResult.contains("notes/hello.txt"));
        assertTrue(readResult.contains("Hello BruceCLI"));
    }

    @Test
    void readsFileWithOffsetLimitAndContinuationHint() throws Exception {
        ToolRegistry registry = new ToolRegistry(tempDir);
        Files.writeString(tempDir.resolve("notes.txt"), "line1\nline2\nline3\nline4");

        String result = registry.executeTool("read_file", Map.of(
            "path", "notes.txt",
            "offset", "2",
            "limit", "2"
        ));

        assertTrue(result.contains("lines 2-3 of 4"));
        assertTrue(result.contains("line2\nline3"));
        assertTrue(result.contains("Use offset=4 to continue"));
    }

    @Test
    void readFileRejectsOffsetBeyondEnd() throws Exception {
        ToolRegistry registry = new ToolRegistry(tempDir);
        Files.writeString(tempDir.resolve("notes.txt"), "line1\nline2");

        String result = registry.executeTool("read_file", Map.of(
            "path", "notes.txt",
            "offset", "5"
        ));

        assertTrue(result.contains("offset 超出文件末尾"));
        assertTrue(result.contains("文件总行数=2"));
    }

    @Test
    void readFileTruncatesLargeOutputWithContinuationHint() throws Exception {
        ToolRegistry registry = new ToolRegistry(tempDir);
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= 500; i++) {
            content.append("line ").append(i).append(" ").append("x".repeat(40)).append('\n');
        }
        Files.writeString(tempDir.resolve("large.txt"), content.toString());

        String result = registry.executeTool("read_file", Map.of("path", "large.txt"));

        assertTrue(result.contains("char limit"));
        assertTrue(result.contains("Use offset="));
    }

    @Test
    void editsFileWhenOldTextMatchesExactlyOnce() throws Exception {
        ToolRegistry registry = new ToolRegistry(tempDir);
        Files.writeString(tempDir.resolve("app.txt"), "hello old world");

        String result = registry.executeTool("edit_file", Map.of(
            "path", "app.txt",
            "old_text", "old",
            "new_text", "new"
        ));

        assertTrue(result.contains("文件已编辑"));
        assertEquals("hello new world", Files.readString(tempDir.resolve("app.txt")));
    }

    @Test
    void editFileDoesNotWriteWhenOldTextIsMissing() throws Exception {
        ToolRegistry registry = new ToolRegistry(tempDir);
        Files.writeString(tempDir.resolve("app.txt"), "hello world");

        String result = registry.executeTool("edit_file", Map.of(
            "path", "app.txt",
            "old_text", "missing",
            "new_text", "new"
        ));

        assertTrue(result.contains("未在文件中找到"));
        assertEquals("hello world", Files.readString(tempDir.resolve("app.txt")));
    }

    @Test
    void editFileDoesNotWriteWhenOldTextMatchesMultiplePlaces() throws Exception {
        ToolRegistry registry = new ToolRegistry(tempDir);
        Files.writeString(tempDir.resolve("app.txt"), "same\nmiddle\nsame");

        String result = registry.executeTool("edit_file", Map.of(
            "path", "app.txt",
            "old_text", "same",
            "new_text", "new"
        ));

        assertTrue(result.contains("匹配多处"));
        assertEquals("same\nmiddle\nsame", Files.readString(tempDir.resolve("app.txt")));
    }

    @Test
    void rejectsPathOutsideWorkspace() {
        ToolRegistry registry = new ToolRegistry(tempDir);

        // 路径逃逸必须被拒绝，否则模型可能通过 ../ 读写工作区外的文件。
        String result = registry.executeTool("read_file", Map.of("path", "../secret.txt"));

        assertTrue(result.contains("路径超出工作目录"));
    }

    @Test
    void createsJavaProjectStructure() {
        ToolRegistry registry = new ToolRegistry(tempDir);

        // create_project 是一个复合工具：一次调用会创建 pom.xml 和标准源码目录。
        String result = registry.executeTool("create_project", Map.of("name", "demo", "type", "java"));

        assertTrue(result.contains("项目已创建"));
        assertTrue(Files.exists(tempDir.resolve("demo/pom.xml")));
        assertTrue(Files.exists(tempDir.resolve("demo/src/main/java/com/example/Hello.java")));
    }

    @Test
    void executesShellCommandInWorkspace() {
        ToolRegistry registry = new ToolRegistry(tempDir);

        // execute_command 用来验证“运行命令 -> 收集输出 -> 返回给模型”的观察能力。
        String result = registry.executeTool("execute_command", Map.of("command", "printf 'Hello BruceCLI'"));

        assertTrue(result.contains("exit code: 0"));
        assertTrue(result.contains("Hello BruceCLI"));
    }

    @Test
    void toolDescriptionsAndPromptGuideLocalShellRouting() {
        ToolRegistry registry = new ToolRegistry(tempDir);

        String executeDescription = registry.getToolDefinitions().stream()
            .filter(tool -> tool.name().equals("execute_command"))
            .findFirst()
            .orElseThrow()
            .description();
        String prompt = registry.buildToolPrompt();

        assertTrue(executeDescription.contains("ls"));
        assertTrue(executeDescription.contains("rg"));
        assertTrue(executeDescription.contains("find"));
        assertTrue(executeDescription.contains("git"));
        assertTrue(executeDescription.contains("build"));
        assertTrue(executeDescription.contains("test"));
        assertTrue(prompt.contains("Available tools:"));
        assertTrue(prompt.contains("execute_command"));
        assertTrue(prompt.contains("Guidelines:"));
        assertTrue(prompt.contains("rg --files"));
        assertTrue(prompt.contains("edit_file"));
        assertTrue(prompt.contains("write_file"));
    }

    @Test
    void toolPromptDeprioritizesFilesystemMcpWhenRegistered() {
        ToolRegistry registry = new ToolRegistry(tempDir);
        registry.register(new Tool(
            "mcp__filesystem__directory_tree",
            "[MCP:filesystem] 获取目录树",
            new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
            args -> "[]"
        ));

        String prompt = registry.buildToolPrompt();

        assertTrue(prompt.contains("mcp__filesystem__*"));
        assertTrue(prompt.contains("内置工具无法满足"));
    }

    @Test
    void dynamicallyRegistersAndUnregistersTools() {
        ToolRegistry registry = new ToolRegistry(tempDir);
        Tool tool = new Tool(
            "dynamic_tool",
            "动态工具",
            new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
            args -> "ok"
        );

        registry.register(tool);
        assertTrue(registry.getToolNames().contains("dynamic_tool"));

        registry.unregister("dynamic_tool");
        assertFalse(registry.getToolNames().contains("dynamic_tool"));
    }
}
