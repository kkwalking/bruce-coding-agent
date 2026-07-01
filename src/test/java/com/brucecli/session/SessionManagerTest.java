package com.brucecli.session;

import com.brucecli.event.BruceEvents;
import com.brucecli.integrated.runtime.AgentMode;
import com.brucecli.llm.ContentPart;
import com.brucecli.llm.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void createsHeaderAndReloadsMessages() throws Exception {
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        SessionManager manager = SessionManager.createNew(home, workspace, AgentMode.REACT);

        assertTrue(Files.exists(manager.currentFile()));
        assertEquals(".bruce", manager.currentFile().getParent().getParent().getParent().getFileName().toString());
        assertEquals(formatWorkspaceDirectory(workspace), manager.currentFile().getParent().getFileName().toString());
        assertTrue(Files.readString(manager.currentFile()).startsWith("{\"type\":\"session\""));

        manager.appendMessage(Message.user("你好"));
        manager.appendMessage(Message.assistant("ok"));
        String sessionId = manager.currentSessionId();

        SessionManager reloaded = createNewAndResume(home, workspace, sessionId);
        assertEquals(List.of("你好", "ok"), reloaded.buildMessages().stream()
            .map(Message::content)
            .toList());
    }

    @Test
    void createNewIgnoresLatestExistingSession() throws Exception {
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        SessionManager manager = SessionManager.createNew(home, workspace, AgentMode.REACT);

        manager.appendMessage(Message.user("旧会话"));
        String oldSessionId = manager.currentSessionId();

        SessionManager fresh = SessionManager.createNew(home, workspace, AgentMode.REACT);

        assertNotEquals(oldSessionId, fresh.currentSessionId());
        assertEquals(List.of(), fresh.buildMessages());
    }

    @Test
    void selectedLeafCreatesBranchOnNextAppendAndSurvivesReload() throws Exception {
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        SessionManager manager = SessionManager.createNew(home, workspace, AgentMode.REACT);

        manager.appendMessage(Message.user("root"));
        String rootLeaf = manager.activeLeafId();
        manager.appendMessage(Message.assistant("main"));
        manager.selectLeaf(rootLeaf);
        manager.appendMessage(Message.user("branch"));

        assertEquals(List.of("root", "branch"), manager.buildMessages().stream()
            .map(Message::content)
            .toList());
        assertTrue(manager.renderTree(AgentMode.REACT).contains("* "));
        String sessionId = manager.currentSessionId();

        SessionManager reloaded = createNewAndResume(home, workspace, sessionId);
        assertEquals(List.of("root", "branch"), reloaded.buildMessages().stream()
            .map(Message::content)
            .toList());
    }

    @Test
    void imageContentIsSanitizedBeforePersistence() throws Exception {
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        SessionManager manager = SessionManager.createNew(home, workspace, AgentMode.REACT);

        manager.appendMessage(Message.user(List.of(
            ContentPart.text("截图"),
            ContentPart.imageUrl("data:image/png;base64,SECRET_IMAGE", "[已附加图片: shot.png]")
        )));

        String raw = Files.readString(manager.currentFile());
        assertFalse(raw.contains("SECRET_IMAGE"));
        assertFalse(raw.contains("\"text\":true"));
        assertFalse(raw.contains("\"image\":false"));
        assertTrue(raw.contains("\"text\":\"[历史图片内容已移除，仅保留文字占位]\""));
        assertTrue(raw.contains("历史图片内容已移除"));
        assertFalse(manager.buildMessages().get(0).hasImageContent());
    }

    @Test
    void modeChangeIsRestoredFromActivePath() throws Exception {
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        SessionManager manager = SessionManager.createNew(home, workspace, AgentMode.REACT);

        manager.appendModeChange(AgentMode.PLAN);
        String sessionId = manager.currentSessionId();

        SessionManager reloaded = createNewAndResume(home, workspace, sessionId);
        assertEquals(AgentMode.PLAN, reloaded.context(AgentMode.REACT).mode());
    }

    @Test
    void unknownHistoricalModeFallsBackToReact() throws Exception {
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        SessionManager manager = SessionManager.createNew(home, workspace, AgentMode.REACT);
        String sessionId = manager.currentSessionId();
        Files.writeString(
            manager.currentFile(),
            "{\"type\":\"mode_change\",\"id\":\"legacy_multi\",\"timestamp\":\"2026-01-01T00:00:00Z\",\"mode\":\"MULTI\"}\n",
            StandardOpenOption.APPEND
        );

        SessionManager reloaded = createNewAndResume(home, workspace, sessionId);

        assertEquals(AgentMode.REACT, reloaded.context(AgentMode.REACT).mode());
    }

    @Test
    void selectingNodeBeforeModeChangeFallsBackToReact() throws Exception {
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        SessionManager manager = SessionManager.createNew(home, workspace, AgentMode.REACT);

        manager.appendMessage(Message.user("root"));
        String rootLeaf = manager.activeLeafId();
        manager.appendModeChange(AgentMode.PLAN);
        manager.appendMessage(Message.user("plan task"));
        manager.selectLeaf(rootLeaf);

        assertEquals(AgentMode.REACT, manager.context(AgentMode.REACT).mode());
    }

    @Test
    void customEntriesAreReloadedButOnlyCustomMessagesEnterContext() throws Exception {
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        SessionManager manager = SessionManager.createNew(home, workspace, AgentMode.REACT);

        manager.appendMessage(Message.user("root"));
        manager.appendCustomEntry("artifact_index", Map.of("count", 2));
        manager.appendCustomMessage("retrieval", "检索上下文", false, Map.of("source", "test"));
        manager.appendSessionInfo("学习会话");
        String sessionId = manager.currentSessionId();

        SessionManager reloaded = createNewAndResume(home, workspace, sessionId);

        assertEquals(List.of("root", "检索上下文"), reloaded.buildMessages().stream()
            .map(Message::content)
            .toList());
        String tree = reloaded.renderTree(AgentMode.REACT);
        assertTrue(tree.contains("custom artifact_index"));
        assertTrue(tree.contains("custom_message 检索上下文"));
        assertTrue(tree.contains("session_info 学习会话"));
    }

    @Test
    void sessionEventRecorderPersistsOnlyDurableCompletedMessages() throws Exception {
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        SessionManager manager = SessionManager.createNew(home, workspace, AgentMode.REACT);
        SessionEventRecorder recorder = new SessionEventRecorder(manager, ignored -> {
        });

        recorder.onEvent(new BruceEvents.MessageDelta("run", "assistant", "content", "delta"));
        recorder.onEvent(new BruceEvents.MessageCompleted("run", Message.assistant("临时错误"), false));
        recorder.onEvent(new BruceEvents.MessageCompleted("run", Message.user("持久用户消息"), true));

        assertEquals(List.of("持久用户消息"), manager.buildMessages().stream()
            .map(Message::content)
            .toList());
        String raw = Files.readString(manager.currentFile());
        assertFalse(raw.contains("delta"));
        assertFalse(raw.contains("临时错误"));
    }

    private static String formatWorkspaceDirectory(Path workspace) {
        String cwd = workspace.toAbsolutePath().normalize().toString();
        return "--" + cwd.replaceFirst("^[/\\\\]", "").replaceAll("[/\\\\:]", "-") + "--";
    }

    private static SessionManager createNewAndResume(Path home, Path workspace, String sessionId) throws Exception {
        SessionManager manager = SessionManager.createNew(home, workspace, AgentMode.REACT);
        manager.resume(sessionId);
        return manager;
    }
}
