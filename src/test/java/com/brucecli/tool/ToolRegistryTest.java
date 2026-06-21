package com.brucecli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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
