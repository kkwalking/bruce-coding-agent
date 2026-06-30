package com.brucecli.tui;

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
    void messageWindowUsesScrollOffsetAndClampsToAvailableHistory() throws Exception {
        try (TestScreen screen = testScreen()) {
            LanternaBruceRenderer renderer = new LanternaBruceRenderer(screen.screen());
            renderer.appendAssistantMessage(String.join("\n",
                "line-1",
                "line-2",
                "line-3",
                "line-4",
                "line-5",
                "line-6",
                "line-7",
                "line-8"
            ));

            assertEquals(5, renderer.maxScrollOffset(80, 3));
            assertEquals(List.of("line-6", "line-7", "line-8"), renderer.visibleMessageLineTexts(80, 3, 0));
            assertEquals(List.of("line-4", "line-5", "line-6"), renderer.visibleMessageLineTexts(80, 3, 2));
            assertEquals(List.of("line-1", "line-2", "line-3"), renderer.visibleMessageLineTexts(80, 3, 99));
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
                "/Users/zhouzekun/code/bruce-coding-agent",
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
    void completionsScrollToKeepSelectedItemVisible() throws Exception {
        try (TestScreen screen = testScreen()) {
            LanternaBruceRenderer renderer = new LanternaBruceRenderer(screen.screen());
            screen.screen().startScreen();
            List<CompletionItem> completions = List.of(
                completion("/cmd0"),
                completion("/cmd1"),
                completion("/cmd2"),
                completion("/cmd3"),
                completion("/cmd4"),
                completion("/cmd5"),
                completion("/cmd6"),
                completion("/cmd7")
            );

            renderer.render("/", 1, completions, 6, 0, false);

            String completionPanel = rowsText(screen.screen(), 13, 18);
            assertFalse(completionPanel.contains("/cmd0"));
            assertTrue(completionPanel.contains("/cmd1"));
            assertTrue(completionPanel.contains("/cmd6"));
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
            "/Users/zhouzekun/code/bruce-coding-agent",
            processedFiles,
            207,
            1306,
            9610,
            "src/main/java/App.java",
            0,
            "indexing"
        );
    }

    private static CompletionItem completion(String value) {
        return new CompletionItem(value, value, "description", "test", true);
    }

    private static String rowsText(TerminalScreen screen, int startRow, int endRow) {
        StringBuilder builder = new StringBuilder();
        for (int row = startRow; row <= endRow; row++) {
            builder.append(rowText(screen, row)).append('\n');
        }
        return builder.toString();
    }

    private static String rowText(TerminalScreen screen, int row) {
        StringBuilder builder = new StringBuilder();
        int columns = screen.getTerminalSize().getColumns();
        for (int column = 0; column < columns; column++) {
            builder.append(screen.getBackCharacter(column, row).getCharacter());
        }
        return builder.toString();
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
