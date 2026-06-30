package com.brucecli.instructions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentInstructionsLoaderTest {
    @TempDir
    Path tempDir;

    private final AgentInstructionsLoader loader = new AgentInstructionsLoader();

    @Test
    void loadsGlobalThenProjectAgentsFromGitRootToWorkspace() throws Exception {
        Path home = tempDir.resolve("home");
        Path repo = tempDir.resolve("repo");
        Path workspace = repo.resolve("service/api");
        Files.createDirectories(repo.resolve(".git"));
        write(home.resolve(".bruce/AGENTS.md"), "global instructions");
        write(repo.resolve("AGENTS.md"), "root instructions");
        write(repo.resolve("service/AGENTS.md"), "service instructions");
        write(workspace.resolve("AGENTS.md"), "workspace instructions");

        AgentInstructionsLoadResult result = loader.load(home, workspace);

        assertEquals("", String.join("", result.diagnostics()));
        assertOrder(
            result.prompt(),
            "global instructions",
            "root instructions",
            "service instructions",
            "workspace instructions"
        );
    }

    @Test
    void ignoresOverrideFilesAndKeepsAtReferencesLiteral() throws Exception {
        Path home = tempDir.resolve("home");
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo.resolve(".git"));
        write(home.resolve(".bruce/AGENTS.override.md"), "ignored global override");
        write(repo.resolve("AGENTS.override.md"), "ignored project override");
        write(home.resolve(".bruce/AGENTS.md"), "global");
        write(repo.resolve("AGENTS.md"), "@/Users/bruce/docs/RTK.md\nproject");

        String prompt = loader.load(home, repo).prompt();

        assertTrue(prompt.contains("global"));
        assertTrue(prompt.contains("@/Users/bruce/docs/RTK.md"));
        assertTrue(prompt.contains("project"));
        assertFalse(prompt.contains("ignored global override"));
        assertFalse(prompt.contains("ignored project override"));
    }

    @Test
    void withoutGitRootLoadsOnlyCurrentWorkspaceAgents() throws Exception {
        Path home = tempDir.resolve("home");
        Path parent = tempDir.resolve("floating");
        Path workspace = parent.resolve("nested");
        write(parent.resolve("AGENTS.md"), "parent instructions");
        write(workspace.resolve("AGENTS.md"), "workspace instructions");

        String prompt = loader.load(home, workspace).prompt();

        assertEquals("workspace instructions", prompt);
    }

    @Test
    void stopsAtPromptByteLimit() throws Exception {
        Path home = tempDir.resolve("home");
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo.resolve(".git"));
        write(home.resolve(".bruce/AGENTS.md"), "a".repeat(AgentInstructionsLoader.MAX_PROMPT_BYTES + 100));
        write(repo.resolve("AGENTS.md"), "project instructions");

        String prompt = loader.load(home, repo).prompt();

        assertEquals(AgentInstructionsLoader.MAX_PROMPT_BYTES, prompt.getBytes(StandardCharsets.UTF_8).length);
        assertFalse(prompt.contains("project instructions"));
    }

    private void write(Path file, String content) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private void assertOrder(String text, String... parts) {
        int previous = -1;
        for (String part : parts) {
            int index = text.indexOf(part);
            assertTrue(index > previous, "Expected order for " + part + " in:\n" + text);
            previous = index;
        }
    }
}
