package com.brucecli.integrated.cli;

import com.brucecli.event.BruceEvent;
import com.brucecli.event.BruceEvents;
import com.brucecli.integrated.runtime.IntegratedRuntime;
import com.brucecli.llm.ChatClient;
import com.brucecli.llm.Message;
import com.brucecli.llm.ToolCall;
import com.brucecli.render.BruceStatusInfo;
import com.brucecli.render.LanternaBruceRenderer;
import com.brucecli.tool.ToolCallResult;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.MouseActionType;
import com.googlecode.lanterna.screen.Screen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BruceTuiApp implements AutoCloseable {
    private static final int HISTORY_SIZE = 2_000;
    private static final int HISTORY_FILE_SIZE = 10_000;
    private static final long RESIZE_CHECK_MILLIS = 250L;
    private static final int SCROLL_LINES = 5;

    private final Screen screen;
    private final LanternaBruceRenderer renderer;
    private final IntegratedRuntime runtime;
    private final ChatClient chatClient;
    private final IntegratedCommandProcessor commands;
    private final Path historyFile;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "bruce-tui-worker");
        thread.setDaemon(true);
        return thread;
    });
    private final List<String> history = new ArrayList<>();
    private final StringBuilder input = new StringBuilder();
    private int cursor;
    private int historyIndex;
    private int selectedCompletion;
    private int scrollOffset;
    private volatile boolean busy;
    private volatile boolean exitRequested;
    private Runnable eventSubscription = () -> {
    };

    public BruceTuiApp(
        Screen screen,
        LanternaBruceRenderer renderer,
        IntegratedRuntime runtime,
        ChatClient chatClient,
        IntegratedCommandProcessor commands,
        Path homeDir
    ) {
        this.screen = screen;
        this.renderer = renderer;
        this.runtime = runtime;
        this.chatClient = chatClient;
        this.commands = commands;
        this.historyFile = resolveHistoryFile(homeDir);
        this.eventSubscription = runtime.subscribe(this::handleRuntimeEvent);
    }

    public void run() throws IOException {
        screen.startScreen();
        loadHistory();
        renderer.start();
        renderer.renderWelcome(status("idle"));
        historyIndex = history.size();
        busy = true;
        renderer.updateStatus(status("starting"));
        executor.submit(this::startRuntime);
        boolean localDirty = true;
        long lastResizeCheck = 0L;

        try {
            while (!exitRequested) {
                long now = System.currentTimeMillis();
                boolean resized = false;
                if (now - lastResizeCheck >= RESIZE_CHECK_MILLIS) {
                    resized = screen.doResizeIfNecessary() != null;
                    lastResizeCheck = now;
                }
                if (localDirty || resized || renderer.consumeDirty()) {
                    scrollOffset = renderer.clampScrollOffset(scrollOffset);
                    renderer.render(input.toString(), cursor, completions(), selectedCompletion, scrollOffset, busy);
                    localDirty = false;
                }
                KeyStroke key = screen.pollInput();
                if (key == null) {
                    continue;
                }
                if (renderer.handleApprovalKey(key)) {
                    localDirty = true;
                    continue;
                }
                handleKey(key, completions());
                localDirty = true;
            }
        } finally {
            saveHistory();
            eventSubscription.run();
            executor.shutdownNow();
            renderer.close();
            screen.stopScreen();
        }
    }

    @Override
    public void close() {
        eventSubscription.run();
        executor.shutdownNow();
    }

    private void startRuntime() {
        try {
            runtime.start();
        } catch (RuntimeException exception) {
            renderer.appendSystemMessage("启动失败: " + exception.getMessage());
        } finally {
            busy = false;
            renderer.updateStatus(status("idle"));
        }
    }

    void handleKey(KeyStroke key, List<CompletionItem> completions) {
        if (key instanceof MouseAction mouseAction) {
            handleMouseAction(mouseAction);
            return;
        }

        KeyType type = key.getKeyType();
        Character character = key.getCharacter();
        if (type == KeyType.Character && character != null) {
            if (key.isCtrlDown() && Character.toLowerCase(character) == 'c') {
                clearInput();
                renderer.appendSystemMessage("输入已清空。");
                return;
            }
            if (key.isCtrlDown() && Character.toLowerCase(character) == 'd') {
                if (input.isEmpty()) {
                    exitRequested = true;
                } else {
                    deleteAtCursor();
                }
                return;
            }
            input.insert(cursor, character.charValue());
            cursor++;
            selectedCompletion = 0;
            return;
        }

        switch (type) {
            case Enter -> submitInput();
            case Backspace -> backspace();
            case Delete -> deleteAtCursor();
            case ArrowLeft -> cursor = Math.max(0, cursor - 1);
            case ArrowRight -> cursor = Math.min(input.length(), cursor + 1);
            case Home -> cursor = 0;
            case End -> cursor = input.length();
            case ArrowUp -> {
                if (!completions.isEmpty()) {
                    selectedCompletion = Math.max(0, selectedCompletion - 1);
                } else {
                    previousHistory();
                }
            }
            case ArrowDown -> {
                if (!completions.isEmpty()) {
                    selectedCompletion = Math.min(completions.size() - 1, selectedCompletion + 1);
                } else {
                    nextHistory();
                }
            }
            case PageUp -> scrollBy(SCROLL_LINES);
            case PageDown -> scrollBy(-SCROLL_LINES);
            case Tab -> applyCompletion(completions);
            case Escape -> selectedCompletion = 0;
            case EOF -> exitRequested = true;
            default -> {
            }
        }
    }

    private void handleMouseAction(MouseAction action) {
        MouseActionType type = action.getActionType();
        if (type == MouseActionType.SCROLL_UP) {
            scrollBy(SCROLL_LINES);
        } else if (type == MouseActionType.SCROLL_DOWN) {
            scrollBy(-SCROLL_LINES);
        }
    }

    private void scrollBy(int lines) {
        scrollOffset = renderer.clampScrollOffset(scrollOffset + lines);
    }

    private void submitInput() {
        if (busy) {
            renderer.appendSystemMessage("当前任务仍在执行中。");
            return;
        }
        String submitted = input.toString().trim();
        clearInput();
        if (submitted.isEmpty()) {
            return;
        }
        addHistory(submitted);
        scrollOffset = 0;
        renderer.appendUserMessage(submitted);
        busy = true;
        renderer.updateStatus(status("running"));
        executor.submit(() -> processInput(submitted));
    }

    private void processInput(String submitted) {
        long startedAt = System.nanoTime();
        try {
            CommandResult command = commands.handle(submitted);
            if (command.exit()) {
                exitRequested = true;
                return;
            }
            if (command.handled()) {
                renderer.appendSystemMessage(command.output());
                return;
            }
            runtime.run(submitted);
        } catch (Exception e) {
            renderer.appendSystemMessage("执行失败: " + e.getMessage());
        } finally {
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
            if (isIndexCommand(submitted)) {
                renderer.updateIndexProgress(null);
            }
            busy = false;
            renderer.updateStatus(status("idle").withElapsedMillis(elapsedMillis));
        }
    }

    private static boolean isIndexCommand(String input) {
        String value = input == null ? "" : input.trim();
        return value.equals("/index") || value.startsWith("/index ");
    }

    private void handleRuntimeEvent(BruceEvent event) {
        if (event instanceof BruceEvents.RunStarted) {
            renderer.appendActivity("思考中...");
        } else if (event instanceof BruceEvents.MessageStarted started) {
            if ("assistant".equals(started.role())) {
                renderer.beginStreamingAssistantMessage();
            }
        } else if (event instanceof BruceEvents.MessageDelta delta) {
            if ("assistant".equals(delta.role()) && "content".equals(delta.channel())) {
                renderer.appendStreamingAssistantDelta(delta.delta());
            }
        } else if (event instanceof BruceEvents.MessageCompleted completed) {
            renderCompletedMessage(completed.message());
        } else if (event instanceof BruceEvents.ToolCallStarted started) {
            renderer.appendActivity("工具开始: " + toolName(started.toolCall()));
        } else if (event instanceof BruceEvents.ToolCallCompleted completed) {
            renderer.appendActivity(toolCompletedText(completed));
        } else if (event instanceof BruceEvents.Activity activity) {
            renderer.appendActivity(activity.message());
        } else if (event instanceof BruceEvents.IndexProgressUpdated progress) {
            renderer.updateIndexProgress(progress.progress());
        }
    }

    private void renderCompletedMessage(Message message) {
        if (message == null) {
            return;
        }
        if ("assistant".equals(message.role())) {
            renderer.finishStreamingAssistantMessage(message.content());
        }
    }

    private String toolCompletedText(BruceEvents.ToolCallCompleted completed) {
        ToolCallResult.Status status = completed.status();
        String suffix = status == ToolCallResult.Status.SUCCESS ? "" : " " + status;
        return "工具完成: " + toolName(completed.toolCall()) + suffix + " (" + completed.durationMillis() + "ms)";
    }

    private String toolName(ToolCall toolCall) {
        if (toolCall == null || toolCall.function() == null) {
            return "unknown";
        }
        return toolCall.function().name();
    }

    private List<CompletionItem> completions() {
        List<CompletionItem> result = BruceCompletionEngine.complete(input.toString(), cursor, runtime);
        if (selectedCompletion >= result.size()) {
            selectedCompletion = Math.max(0, result.size() - 1);
        }
        return result;
    }

    private void applyCompletion(List<CompletionItem> completions) {
        if (completions.isEmpty()) {
            return;
        }
        CompletionItem item = completions.get(Math.max(0, Math.min(selectedCompletion, completions.size() - 1)));
        String completed = BruceCompletionEngine.applyCompletion(input.toString(), cursor, item);
        input.setLength(0);
        input.append(completed);
        cursor = input.length();
        selectedCompletion = 0;
    }

    private void clearInput() {
        input.setLength(0);
        cursor = 0;
        selectedCompletion = 0;
        historyIndex = history.size();
    }

    private void backspace() {
        if (cursor <= 0) {
            return;
        }
        input.deleteCharAt(cursor - 1);
        cursor--;
        selectedCompletion = 0;
    }

    private void deleteAtCursor() {
        if (cursor >= input.length()) {
            return;
        }
        input.deleteCharAt(cursor);
        selectedCompletion = 0;
    }

    private void previousHistory() {
        if (history.isEmpty()) {
            return;
        }
        historyIndex = Math.max(0, historyIndex - 1);
        replaceInput(history.get(historyIndex));
    }

    private void nextHistory() {
        if (history.isEmpty()) {
            return;
        }
        historyIndex = Math.min(history.size(), historyIndex + 1);
        replaceInput(historyIndex >= history.size() ? "" : history.get(historyIndex));
    }

    private void replaceInput(String value) {
        input.setLength(0);
        input.append(value);
        cursor = input.length();
        selectedCompletion = 0;
    }

    private void addHistory(String value) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return;
        }
        if (!history.isEmpty() && history.get(history.size() - 1).equals(normalized)) {
            return;
        }
        history.add(normalized);
        while (history.size() > HISTORY_SIZE) {
            history.remove(0);
        }
        historyIndex = history.size();
    }

    private void loadHistory() {
        try {
            Files.createDirectories(historyFile.getParent());
            if (Files.exists(historyFile)) {
                history.clear();
                List<String> lines = Files.readAllLines(historyFile);
                int start = Math.max(0, lines.size() - HISTORY_SIZE);
                for (String line : lines.subList(start, lines.size())) {
                    if (line != null && !line.isBlank()) {
                        history.add(line.trim());
                    }
                }
            }
        } catch (IOException ignored) {
            // History is convenience only.
        }
    }

    private void saveHistory() {
        try {
            Files.createDirectories(historyFile.getParent());
            List<String> lines = history.size() > HISTORY_FILE_SIZE
                ? history.subList(history.size() - HISTORY_FILE_SIZE, history.size())
                : history;
            Files.write(historyFile, lines);
        } catch (IOException ignored) {
            // Best-effort persistence only.
        }
    }

    static Path resolveHistoryFile(Path homeDir) {
        Path base = homeDir == null ? Path.of(System.getProperty("user.home")) : homeDir;
        return base.resolve(".brucecli").resolve("history").toAbsolutePath().normalize();
    }

    int scrollOffset() {
        return scrollOffset;
    }

    private BruceStatusInfo status(String phase) {
        return BruceStatusInfo.from(runtime, chatClient, phase);
    }

}
