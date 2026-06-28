package com.brucecli.session;

import com.brucecli.integrated.runtime.AgentMode;
import com.brucecli.llm.ContentPart;
import com.brucecli.llm.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void createsHeaderAndReloadsMessages() throws Exception {
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        SessionManager manager = SessionManager.openLatestOrCreate(home, workspace, AgentMode.REACT);

        assertTrue(Files.exists(manager.currentFile()));
        assertEquals(formatWorkspaceDirectory(workspace), manager.currentFile().getParent().getFileName().toString());
        assertTrue(Files.readString(manager.currentFile()).startsWith("{\"type\":\"session\""));

        manager.appendMessage(Message.user("你好"));
        manager.appendMessage(Message.assistant("ok"));

        SessionManager reloaded = SessionManager.openLatestOrCreate(home, workspace, AgentMode.REACT);
        assertEquals(List.of("你好", "ok"), reloaded.buildMessages().stream()
            .map(Message::content)
            .toList());
    }

    @Test
    void selectedLeafCreatesBranchOnNextAppendAndSurvivesReload() throws Exception {
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        SessionManager manager = SessionManager.openLatestOrCreate(home, workspace, AgentMode.REACT);

        manager.appendMessage(Message.user("root"));
        String rootLeaf = manager.activeLeafId();
        manager.appendMessage(Message.assistant("main"));
        manager.selectLeaf(rootLeaf);
        manager.appendMessage(Message.user("branch"));

        assertEquals(List.of("root", "branch"), manager.buildMessages().stream()
            .map(Message::content)
            .toList());
        assertTrue(manager.renderTree(AgentMode.REACT).contains("* "));

        SessionManager reloaded = SessionManager.openLatestOrCreate(home, workspace, AgentMode.REACT);
        assertEquals(List.of("root", "branch"), reloaded.buildMessages().stream()
            .map(Message::content)
            .toList());
    }

    @Test
    void imageContentIsSanitizedBeforePersistence() throws Exception {
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        SessionManager manager = SessionManager.openLatestOrCreate(home, workspace, AgentMode.REACT);

        manager.appendMessage(Message.user(List.of(
            ContentPart.text("截图"),
            ContentPart.imageUrl("data:image/png;base64,SECRET_IMAGE", "[已附加图片: shot.png]")
        )));

        String raw = Files.readString(manager.currentFile());
        assertFalse(raw.contains("SECRET_IMAGE"));
        assertTrue(raw.contains("历史图片内容已移除"));
        assertFalse(manager.buildMessages().get(0).hasImageContent());
    }

    @Test
    void modeChangeIsRestoredFromActivePath() throws Exception {
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        SessionManager manager = SessionManager.openLatestOrCreate(home, workspace, AgentMode.REACT);

        manager.appendModeChange(AgentMode.PLAN);

        SessionManager reloaded = SessionManager.openLatestOrCreate(home, workspace, AgentMode.REACT);
        assertEquals(AgentMode.PLAN, reloaded.context(AgentMode.REACT).mode());
    }

    @Test
    void selectingNodeBeforeModeChangeFallsBackToReact() throws Exception {
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        SessionManager manager = SessionManager.openLatestOrCreate(home, workspace, AgentMode.REACT);

        manager.appendMessage(Message.user("root"));
        String rootLeaf = manager.activeLeafId();
        manager.appendModeChange(AgentMode.PLAN);
        manager.appendMessage(Message.user("plan task"));
        manager.selectLeaf(rootLeaf);

        assertEquals(AgentMode.REACT, manager.context(AgentMode.REACT).mode());
    }

    @Test
    void legacyBase64WorkspaceDirectoryIsStillReadable() throws Exception {
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        Path legacyDirectory = home.resolve(".brucecli")
            .resolve("sessions")
            .resolve(legacyWorkspaceDirectory(workspace));
        Files.createDirectories(legacyDirectory);
        Files.writeString(legacyDirectory.resolve("legacy.jsonl"), """
            {"type":"session","version":1,"id":"legacy","createdAt":"2026-06-27T00:00:00Z","cwd":"%s","name":null}
            {"type":"message","id":"e_legacy","timestamp":"2026-06-27T00:00:01Z","message":{"role":"user","content":"旧会话","reasoningContent":""}}
            """.formatted(workspace.toAbsolutePath().normalize()));

        SessionManager manager = SessionManager.openLatestOrCreate(home, workspace, AgentMode.REACT);

        assertEquals("legacy", manager.currentSessionId());
        assertEquals(List.of("旧会话"), manager.buildMessages().stream().map(Message::content).toList());

        manager.createNew(AgentMode.REACT);
        assertEquals(formatWorkspaceDirectory(workspace), manager.currentFile().getParent().getFileName().toString());
    }

    private static String formatWorkspaceDirectory(Path workspace) {
        String cwd = workspace.toAbsolutePath().normalize().toString();
        return "--" + cwd.replaceFirst("^[/\\\\]", "").replaceAll("[/\\\\:]", "-") + "--";
    }

    private static String legacyWorkspaceDirectory(Path workspace) {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(workspace.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
    }
}
