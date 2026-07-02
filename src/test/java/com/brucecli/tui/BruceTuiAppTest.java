package com.brucecli.tui;

import com.brucecli.event.BruceEvents;
import com.brucecli.integrated.cli.IntegratedCliTestSupport;
import com.brucecli.llm.ChatClient;
import com.brucecli.llm.ChatResponse;
import com.brucecli.llm.FunctionCall;
import com.brucecli.llm.Message;
import com.brucecli.llm.ToolCall;
import com.brucecli.llm.ToolDefinition;
import com.brucecli.tool.ToolCallResult;
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

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void resumeCommandReplaysSessionHistoryIntoOutputPanel() throws Exception {
        String sessionId;
        try (IntegratedCliTestSupport.TestContext context = IntegratedCliTestSupport.context(tempDir)) {
            assertEquals("ok", context.runtime().run("保存这一轮"));
            sessionId = context.runtime().sessionContext().sessionId();
        }

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
            renderer.appendSystemMessage("旧面板内容");

            type(app, "/resume " + sessionId);
            app.handleKey(new KeyStroke(KeyType.Enter), List.of());
            waitForMessage(renderer, "已恢复 session");

            List<String> messages = renderer.messageTexts();
            assertTrue(messages.contains("❯ 保存这一轮"));
            assertTrue(messages.contains("ok"));
            assertTrue(messages.stream().anyMatch(message -> message.contains("已恢复 session")));
            assertFalse(messages.stream().anyMatch(message -> message.contains("旧面板内容")));
            assertFalse(messages.stream().anyMatch(message -> message.contains("/resume ")));
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

    @Test
    void toolLifecycleUpdatesSingleActivityLine() throws Exception {
        QueueChatClient chatClient = new QueueChatClient(
            new ChatResponse(
                "",
                List.of(toolCall("call-1", "write_file", "{\"path\":\"demo.txt\",\"content\":\"hello\"}"))
            ),
            new ChatResponse("文件已经写好。", List.of())
        );
        try (
            IntegratedCliTestSupport.TestContext context = IntegratedCliTestSupport.context(
                tempDir,
                new PrintStream(OutputStream.nullOutputStream()),
                chatClient
            );
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
            assertEquals("文件已经写好。", context.runtime().run("写文件"));

            List<String> messages = renderer.messageTexts();
            assertTrue(messages.contains("* 工具调用: write_file (完成)"));
            assertEquals(1, messages.stream().filter(message -> message.contains("工具调用: write_file")).count());
            assertFalse(messages.stream().anyMatch(message -> message.contains("工具开始")));
            assertFalse(messages.stream().anyMatch(message -> message.contains("工具完成")));
        }
    }

    @Test
    void failedToolLifecycleUpdatesSingleActivityLine() throws Exception {
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
            ToolCall toolCall = toolCall("call-failed", "read_file", "{\"path\":\"missing.txt\"}");

            app.handleRuntimeEvent(new BruceEvents.ToolCallStarted("run-failed", toolCall));
            app.handleRuntimeEvent(new BruceEvents.ToolCallCompleted(
                "run-failed",
                ToolCallResult.failed(toolCall, new IllegalStateException("boom"), 7)
            ));

            assertEquals(List.of("* 工具调用: read_file (失败)"), renderer.messageTexts());
        }
    }

    private static void waitForMessage(LanternaBruceRenderer renderer, String text) throws Exception {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            if (renderer.messageTexts().stream().anyMatch(message -> message.contains(text))) {
                return;
            }
            Thread.sleep(25);
        }
        assertTrue(
            renderer.messageTexts().stream().anyMatch(message -> message.contains(text)),
            () -> "Expected message containing: " + text + ", got: " + renderer.messageTexts()
        );
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

    private static ToolCall toolCall(String id, String name, String arguments) {
        return new ToolCall(id, new FunctionCall(name, arguments));
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

    private static class QueueChatClient implements ChatClient {
        private final Queue<ChatResponse> responses = new ArrayDeque<>();

        QueueChatClient(ChatResponse... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<ToolDefinition> tools) throws IOException {
            ChatResponse response = responses.poll();
            if (response == null) {
                throw new IOException("no queued response");
            }
            return response;
        }
    }
}
