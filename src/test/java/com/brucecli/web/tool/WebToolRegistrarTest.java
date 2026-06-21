package com.brucecli.web.tool;

import com.brucecli.tool.ToolRegistry;
import com.brucecli.web.search.WebSearchConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class WebToolRegistrarTest {
    @TempDir
    Path tempDir;

    @Test
    void registersSearchAndFetchToolsWithFriendlyGlmHint() {
        ToolRegistry registry = new ToolRegistry(tempDir);

        WebToolRegistrar.register(registry, WebSearchConfig.empty());

        assertTrue(registry.getToolNames().contains("web_search"));
        assertTrue(registry.getToolNames().contains("web_fetch"));
        String result = registry.executeTool("web_search", Map.of("query", "bruce cli"));
        assertTrue(result.contains("GLM_API_KEY"));
        assertTrue(result.contains("DEEPSEEK_API_KEY"));
    }
}
