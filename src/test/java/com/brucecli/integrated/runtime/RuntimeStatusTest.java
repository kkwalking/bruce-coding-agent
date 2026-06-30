package com.brucecli.integrated.runtime;

import com.brucecli.memory.core.MemoryStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeStatusTest {
    @Test
    void displayHidesInternalAndMcpTools() {
        RuntimeStatus status = new RuntimeStatus(
            AgentMode.REACT,
            "model",
            "provider",
            Path.of("workspace").toAbsolutePath(),
            emptyMemoryStatus(),
            false,
            true,
            "test",
            "未配置",
            true,
            true,
            4,
            Duration.ofSeconds(30),
            false,
            0,
            List.of(
                "read_file",
                "load_skill",
                "read_skill_resource",
                "save_long_term_memory",
                "mcp__filesystem__read_file",
                "web_search"
            )
        );

        assertTrue(status.toolNames().contains("load_skill"));
        assertTrue(status.toolNames().contains("mcp__filesystem__read_file"));

        String toolsLine = status.toDisplayString().lines()
            .filter(line -> line.stripLeading().startsWith("Tools:"))
            .findFirst()
            .orElseThrow();

        assertEquals("Tools: read_file, web_search", toolsLine.strip());
    }

    private static MemoryStatus emptyMemoryStatus() {
        return new MemoryStatus(
            0,
            0,
            0,
            0.0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            false
        );
    }
}
