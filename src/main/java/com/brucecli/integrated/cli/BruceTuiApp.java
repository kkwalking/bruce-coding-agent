package com.brucecli.integrated.cli;

import com.brucecli.integrated.runtime.IntegratedRuntime;
import com.brucecli.llm.ChatClient;
import com.brucecli.render.BruceStatusInfo;
import com.brucecli.render.LanternaBruceRenderer;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
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
    }

    public void run() throws IOException {
        screen.startScreen();
        loadHistory();
        renderer.start();
        renderer.renderWelcome(status("idle"));
        historyIndex = history.size();
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
            executor.shutdownNow();
            renderer.close();
            screen.stopScreen();
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void handleKey(KeyStroke key, List<CompletionItem> completions) {
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
            case PageUp -> scrollOffset += 5;
            case PageDown -> scrollOffset = Math.max(0, scrollOffset - 5);
            case Tab -> applyCompletion(completions);
            case Escape -> selectedCompletion = 0;
            case EOF -> exitRequested = true;
            default -> {
            }
        }
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
        renderer.appendUserMessage(submitted);
        busy = true;
        renderer.updateStatus(status("running"));
        if (!isIndexCommand(submitted)) {
            renderer.appendActivity("思考中...");
        }
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
            renderer.appendAssistantMessage(runtime.run(submitted));
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

    private BruceStatusInfo status(String phase) {
        return BruceStatusInfo.from(runtime, chatClient, phase);
    }

}
