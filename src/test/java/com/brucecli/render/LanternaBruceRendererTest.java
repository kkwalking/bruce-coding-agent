package com.brucecli.render;

import com.brucecli.approval.ApprovalRequest;
import com.brucecli.approval.ApprovalResult;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanternaBruceRendererTest {
    @Test
    void layoutKeepsInputAndStatusDockedAtBottom() {
        LanternaBruceRenderer.TuiLayout layout = LanternaBruceRenderer.layout(new TerminalSize(80, 24));

        assertEquals(20, layout.messageRows());
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
