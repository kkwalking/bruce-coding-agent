package com.brucecli.render;

import com.brucecli.approval.ApprovalRequest;
import com.brucecli.approval.ApprovalResult;
import com.brucecli.rag.model.IndexProgress;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanternaBruceRendererTest {
    @Test
    void layoutKeepsInputAndStatusDockedAtBottom() {
        LanternaBruceRenderer.TuiLayout layout = LanternaBruceRenderer.layout(new TerminalSize(80, 24));

        assertEquals(19, layout.messageRows());
        assertEquals(19, layout.indexStatusRow());
        assertEquals(20, layout.inputTop());
        assertEquals(21, layout.inputLine());
        assertEquals(22, layout.inputBottom());
        assertEquals(23, layout.statusRow());
    }

    @Test
    void inputFrameLineUsesSolidRule() {
        String line = LanternaBruceRenderer.inputFrameLine(40);

        assertEquals(40, line.length());
        assertTrue(line.chars().allMatch(ch -> ch == '━'));
    }

    @Test
    void inputFrameIsNotStoredAsMessage() throws Exception {
        try (TestScreen screen = testScreen()) {
            LanternaBruceRenderer renderer = new LanternaBruceRenderer(screen.screen());
            renderer.appendUserMessage("hello");

            assertTrue(renderer.messageTexts().contains("❯ hello"));
            assertFalse(renderer.messageTexts().contains(LanternaBruceRenderer.inputFrameLine(40)));
        }
    }

    @Test
    void streamDecodesUtf8Lines() throws Exception {
        try (TestScreen screen = testScreen()) {
            LanternaBruceRenderer renderer = new LanternaBruceRenderer(screen.screen());

            renderer.stream().print("中文 MCP server 已启动");
            renderer.stream().flush();

            assertTrue(renderer.messageTexts().contains("* 中文 MCP server 已启动"));
        }
    }

    @Test
    void streamingAssistantDeltasProduceSingleFinalMessage() throws Exception {
        try (TestScreen screen = testScreen()) {
            LanternaBruceRenderer renderer = new LanternaBruceRenderer(screen.screen());

            renderer.beginStreamingAssistantMessage();
            renderer.appendStreamingAssistantDelta("你");
            renderer.appendStreamingAssistantDelta("好");
            renderer.finishStreamingAssistantMessage("你好");

            assertEquals(List.of("你好"), renderer.messageTexts());
        }
    }

    @Test
    void completedAssistantWithoutDeltasStillRenders() throws Exception {
        try (TestScreen screen = testScreen()) {
            LanternaBruceRenderer renderer = new LanternaBruceRenderer(screen.screen());

            renderer.finishStreamingAssistantMessage("完整回答");

            assertEquals(List.of("完整回答"), renderer.messageTexts());
        }
    }

    @Test
    void blankStreamingAssistantIsRemovedAroundToolActivity() throws Exception {
        try (TestScreen screen = testScreen()) {
            LanternaBruceRenderer renderer = new LanternaBruceRenderer(screen.screen());

            renderer.beginStreamingAssistantMessage();
            renderer.appendActivity("工具开始: write_file");
            renderer.finishStreamingAssistantMessage("");

            assertEquals(List.of("* 工具开始: write_file"), renderer.messageTexts());
        }
    }

    @Test
    void indexProgressRendersOutsideMessages() throws Exception {
        try (TestScreen screen = testScreen()) {
            LanternaBruceRenderer renderer = new LanternaBruceRenderer(screen.screen());

            renderer.updateIndexProgress(new IndexProgress(
                "/Users/zhouzekun/code/bruce-cli",
                190,
                207,
                1306,
                9610,
                "src/main/java/App.java",
                2,
                "indexing"
            ));

            assertTrue(renderer.indexProgressText().contains("RAG index: 190/207 files"));
            assertTrue(renderer.indexProgressText().contains("warnings=2"));
            assertTrue(renderer.messageTexts().stream().noneMatch(message -> message.contains("[index] 已处理")));
        }
    }

    @Test
    void indexProgressRenderingIsThrottledButClearAlwaysRenders() throws Exception {
        try (TestScreen screen = testScreen()) {
            LanternaBruceRenderer renderer = new LanternaBruceRenderer(screen.screen());
            renderer.consumeDirty();

            renderer.updateIndexProgress(indexProgress(1));
            assertTrue(renderer.consumeDirty());

            renderer.updateIndexProgress(indexProgress(2));
            assertFalse(renderer.consumeDirty());

            renderer.updateIndexProgress(null);
            assertTrue(renderer.consumeDirty());
        }
    }

    @Test
    void cursorPositionUsesTerminalColumnWidth() throws Exception {
        try (TestScreen screen = testScreen()) {
            LanternaBruceRenderer renderer = new LanternaBruceRenderer(screen.screen());
            screen.screen().startScreen();
            String input = "这里出现了光标";
            int cursor = "这里出现了".length();

            renderer.render(input, cursor, List.of(), 0, 0, false);

            TerminalPosition position = screen.screen().getCursorPosition();
            assertEquals(2 + LanternaBruceRenderer.displayColumnWidth("这里出现了"), position.getColumn());
            assertEquals(21, position.getRow());
        }
    }

    @Test
    void approvalDialogCompletesFromKeyInput() throws Exception {
        try (TestScreen screen = testScreen()) {
            LanternaBruceRenderer renderer = new LanternaBruceRenderer(screen.screen());
            CompletableFuture<ApprovalResult> future = CompletableFuture.supplyAsync(() ->
                renderer.requestApproval(ApprovalRequest.of("write_file", "{\"path\":\"safe.txt\"}", "检查路径"))
            );

            waitForApprovalDialog(renderer);
            assertTrue(renderer.hasApprovalDialog());
            renderer.handleApprovalKey(new KeyStroke('a', false, false));

            assertEquals(ApprovalResult.Decision.APPROVED_ALL, future.get(2, TimeUnit.SECONDS).decision());
        }
    }

    private static void waitForApprovalDialog(LanternaBruceRenderer renderer) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!renderer.hasApprovalDialog() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static IndexProgress indexProgress(int processedFiles) {
        return new IndexProgress(
            "/Users/zhouzekun/code/bruce-cli",
            processedFiles,
            207,
            1306,
            9610,
            "src/main/java/App.java",
            0,
            "indexing"
        );
    }

    private static TestScreen testScreen() throws Exception {
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(80, 24));
        TerminalScreen screen = new TerminalScreen(terminal);
        return new TestScreen(screen);
    }

    private record TestScreen(TerminalScreen screen) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            screen.close();
        }
    }
}
