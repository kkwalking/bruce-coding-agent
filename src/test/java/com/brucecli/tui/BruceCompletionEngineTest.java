package com.brucecli.tui;

import com.brucecli.integrated.cli.IntegratedCliTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BruceCompletionEngineTest {
    @TempDir
    Path tempDir;

    @Test
    void completesTopLevelSlashCommandsAndSubcommands() throws Exception {
        try (IntegratedCliTestSupport.TestContext context = IntegratedCliTestSupport.context(tempDir)) {
            List<String> topLevel = values(BruceCompletionEngine.complete("/", 1, context.runtime()));
            assertTrue(topLevel.contains("/help"));
            assertTrue(topLevel.contains("/status"));
            assertTrue(topLevel.contains("/session"));
            assertTrue(topLevel.contains("/tree "));
            assertTrue(topLevel.contains("/model"));
            assertFalse(topLevel.contains("/rag "));
            assertFalse(topLevel.contains("/index "));
            assertFalse(topLevel.contains("/search "));
            assertFalse(topLevel.contains("/graph "));

            List<String> ragActions = values(BruceCompletionEngine.complete("/rag ", 5, context.runtime()));
            assertTrue(ragActions.isEmpty());

            List<String> webCommands = values(BruceCompletionEngine.complete("/web s", 6, context.runtime()));
            assertTrue(webCommands.contains("search "));
            assertTrue(webCommands.contains("status"));
        }
    }

    @Test
    void completesModelSelectorWithProviderDisplay() throws Exception {
        try (IntegratedCliTestSupport.TestContext context = IntegratedCliTestSupport.context(tempDir)) {
            List<CompletionItem> candidates = BruceCompletionEngine.complete("/model", 6, context.runtime());

            assertTrue(values(candidates).contains("glm/glm-5.1"));
            assertTrue(candidates.stream().anyMatch(candidate -> candidate.display().equals("glm-5.1 [glm]")));
            assertTrue(candidates.stream().anyMatch(candidate -> candidate.description().equals("当前模型")));
            assertEquals("/model glm/glm-5.1", BruceCompletionEngine.applyCompletion(
                "/model",
                6,
                new CompletionItem("glm/glm-5.1", "glm-5.1 [glm]", "", "Model", true)
            ));
        }
    }

    @Test
    void completesSkillAndMcpNames() throws Exception {
        IntegratedCliTestSupport.writeSkill(tempDir, "java-review", "审查 Java 代码", "review");
        IntegratedCliTestSupport.writeDisabledMcpServer(tempDir, "filesystem");
        try (IntegratedCliTestSupport.TestContext context = IntegratedCliTestSupport.context(tempDir)) {
            assertTrue(values(BruceCompletionEngine.complete("/skill show ja", 14, context.runtime())).contains("java-review"));
            assertTrue(values(BruceCompletionEngine.complete("$ja", 3, context.runtime())).contains("$java-review "));
            assertTrue(values(BruceCompletionEngine.complete("/mcp restart ", 13, context.runtime())).contains("filesystem"));
        }
    }

    @Test
    void completesImagePathAndAppliesCompletion() throws Exception {
        Files.writeString(tempDir.resolve("shot.png"), "fake");
        try (IntegratedCliTestSupport.TestContext context = IntegratedCliTestSupport.context(tempDir)) {
            String input = "@image:<" + tempDir.resolve("sh");
            List<CompletionItem> candidates = BruceCompletionEngine.complete(input, input.length(), context.runtime());

            assertTrue(values(candidates).stream().anyMatch(value -> value.endsWith("shot.png")));
            assertEquals("/help", BruceCompletionEngine.applyCompletion("/", 1, new CompletionItem("/help", "/help", "", "", true)));
        }
    }

    private static List<String> values(List<CompletionItem> candidates) {
        return candidates.stream().map(CompletionItem::value).toList();
    }
}
