package com.brucecli.tui;

import com.brucecli.integrated.cli.IntegratedCliTestSupport;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BruceTuiAppTest {
    @TempDir
    Path tempDir;

    @Test
    void storesInputHistoryUnderBruceCliDirectory() {
        assertEquals(
            tempDir.resolve(".bruce/history").toAbsolutePath().normalize(),
            BruceTuiApp.resolveHistoryFile(tempDir)
        );
    }

    @Test
    void mouseWheelScrollsMessageAreaWithinBounds() throws Exception {
        try (
            IntegratedCliTestSupport.TestContext context = IntegratedCliTestSupport.context(tempDir);
            TestScreen testScreen = testScreen();
            LanternaBruceRenderer renderer = new LanternaBruceRenderer(testScreen.screen());
            BruceTuiApp app = new BruceTuiApp(
                testScreen.screen(),
                renderer,
                context.runtime(),
                context.chatClient(),
                input -> {
                    var result = context.commands().handle(input);
                    return new TuiCommandResult(result.handled(), result.exit(), result.output());
                },
                tempDir
            )
        ) {
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

            app.handleKey(mouse(MouseActionType.SCROLL_UP), List.of());

            assertTrue(app.scrollOffset() > 0);

            app.handleKey(mouse(MouseActionType.SCROLL_DOWN), List.of());
            app.handleKey(mouse(MouseActionType.SCROLL_DOWN), List.of());

            assertEquals(0, app.scrollOffset());
        }
    }

    @Test
    void enterOnModelCommandOpensSelectorThenSwitchesModel() throws Exception {
        try (
            IntegratedCliTestSupport.TestContext context = IntegratedCliTestSupport.context(tempDir);
            TestScreen testScreen = testScreen();
            LanternaBruceRenderer renderer = new LanternaBruceRenderer(testScreen.screen());
        ) {
            List<String> submittedInputs = new ArrayList<>();
            BruceTuiApp app = new BruceTuiApp(
                testScreen.screen(),
                renderer,
                context.runtime(),
                context.chatClient(),
                input -> {
                    submittedInputs.add(input);
                    var result = context.commands().handle(input);
                    return new TuiCommandResult(result.handled(), result.exit(), result.output());
                },
                tempDir
            );
            try (app) {
                type(app, "/model");
                assertTrue(app.completions().isEmpty());

                app.handleKey(new KeyStroke(KeyType.ArrowDown), app.completions());
                assertEquals(0, app.selectedCompletion());

                app.handleKey(new KeyStroke(KeyType.Enter), app.completions());
                assertEquals("/model ", app.inputText());
                List<CompletionItem> opened = app.completions();
                assertEquals(2, opened.size());
                app.handleKey(new KeyStroke(KeyType.ArrowDown), opened);
                assertEquals(1, app.selectedCompletion());
                app.handleKey(new KeyStroke(KeyType.Enter), app.completions());
                waitForModel(context, "glm", "glm-5.1");

                assertTrue(submittedInputs.contains("/model glm/glm-5.1"));
                assertEquals("glm", context.runtime().currentModel().provider());
                assertEquals("glm-5.1", context.runtime().currentModel().model());
            }
        }
    }

    private static MouseAction mouse(MouseActionType type) {
        return new MouseAction(type, 0, new TerminalPosition(0, 0));
    }

    private static void type(BruceTuiApp app, String text) {
        for (char ch : text.toCharArray()) {
            app.handleKey(new KeyStroke(ch, false, false), List.of());
        }
    }

    private static void waitForModel(IntegratedCliTestSupport.TestContext context, String provider, String model)
        throws Exception {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            if (provider.equals(context.runtime().currentModel().provider())
                && model.equals(context.runtime().currentModel().model())) {
                return;
            }
            Thread.sleep(25);
        }
    }

    private static TestScreen testScreen() throws Exception {
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(new TerminalSize(20, 8));
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
