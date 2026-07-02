package com.brucecli.tui;

import com.brucecli.event.BruceEvent;
import com.brucecli.event.BruceEvents;
import com.brucecli.integrated.runtime.IntegratedRuntime;
import com.brucecli.llm.ChatClient;
import com.brucecli.llm.Message;
import com.brucecli.llm.ToolCall;
import com.brucecli.render.BruceStatusInfo;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final TuiCommandHandler commands;
    private final Path historyFile;
    private final Map<String, Integer> toolActivityIndexes = new HashMap<>();
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
    private boolean modelSelectorOpen;
    private Runnable eventSubscription = () -> {
    };

    public BruceTuiApp(
        Screen screen,
        LanternaBruceRenderer renderer,
        IntegratedRuntime runtime,
        ChatClient chatClient,
        TuiCommandHandler commands,
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
            updateModelSelectorStateAfterEdit();
            return;
        }

        switch (type) {
            case Enter -> {
                if (!handleModelSelectorEnter(completions)) {
                    submitInput();
                }
            }
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
            case Escape -> {
                selectedCompletion = 0;
                modelSelectorOpen = false;
            }
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
            TuiCommandResult command = commands.handle(submitted);
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

    void handleRuntimeEvent(BruceEvent event) {
        if (event instanceof BruceEvents.RunStarted) {
            toolActivityIndexes.clear();
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
            int index = renderer.appendActivityAndReturnIndex(toolActivityText(started.toolCall(), "处理中"));
            if (index >= 0) {
                toolActivityIndexes.put(toolActivityKey(started.runId(), started.toolCall()), index);
            }
        } else if (event instanceof BruceEvents.ToolCallCompleted completed) {
            String text = toolActivityText(completed.toolCall(), toolCompletedStatus(completed.status()));
            Integer index = toolActivityIndexes.remove(toolActivityKey(completed.runId(), completed.toolCall()));
            if (index == null || !renderer.replaceActivity(index, text)) {
                renderer.appendActivity(text);
            }
        } else if (event instanceof BruceEvents.Activity activity) {
            renderer.appendActivity(activity.message());
        } else if (event instanceof BruceEvents.SessionChanged changed) {
            replaySessionHistory(changed);
        } else if (event instanceof BruceEvents.IndexProgressUpdated progress) {
            renderer.updateIndexProgress(progress.progress());
        }
    }

    private void replaySessionHistory(BruceEvents.SessionChanged changed) {
        if (!("resume".equals(changed.reason()) || "compact".equals(changed.reason())) || changed.context() == null) {
            return;
        }
        renderer.clearMessages();
        scrollOffset = 0;
        List<Message> messages = changed.context().messages();
        if (messages == null) {
            return;
        }
        for (Message message : messages) {
            renderSessionMessage(message);
        }
    }

    private void renderSessionMessage(Message message) {
        if (message == null) {
            return;
        }
        if ("user".equals(message.role())) {
            renderer.appendUserMessage(message.content());
        } else if ("assistant".equals(message.role())
            && message.content() != null
            && !message.content().isBlank()) {
            renderer.appendAssistantMessage(message.content());
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

    private String toolActivityKey(String runId, ToolCall toolCall) {
        String safeRunId = runId == null ? "" : runId;
        String toolCallId = toolCall == null || toolCall.id() == null ? toolName(toolCall) : toolCall.id();
        return safeRunId + ":" + toolCallId;
    }

    private String toolActivityText(ToolCall toolCall, String status) {
        return "工具调用: " + toolName(toolCall) + " (" + status + ")";
    }

    private String toolCompletedStatus(ToolCallResult.Status status) {
        return status == ToolCallResult.Status.SUCCESS ? "完成" : "失败";
    }

    private String toolName(ToolCall toolCall) {
        if (toolCall == null || toolCall.function() == null) {
            return "unknown";
        }
        return toolCall.function().name();
    }

    List<CompletionItem> completions() {
        if (!modelSelectorOpen && isExactModelCommand(input.toString())) {
            return List.of();
        }
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
        updateModelSelectorStateAfterEdit();
    }

    private boolean handleModelSelectorEnter(List<CompletionItem> completions) {
        String value = input.toString();
        if (isExactModelCommand(value) && !modelSelectorOpen) {
            openModelSelector();
            return true;
        }
        List<CompletionItem> modelCompletions = completions == null
            ? List.of()
            : completions.stream().filter(item -> "Model".equals(item.group())).toList();
        if ((modelSelectorOpen || startsWithModelCommand(value)) && !modelCompletions.isEmpty()) {
            CompletionItem item = modelCompletions.get(Math.max(0, Math.min(selectedCompletion, modelCompletions.size() - 1)));
            replaceInput("/model " + item.value());
            submitInput();
            return true;
        }
        return false;
    }

    private void openModelSelector() {
        replaceInput("/model ");
        modelSelectorOpen = true;
        List<CompletionItem> modelCompletions = BruceCompletionEngine.complete(input.toString(), cursor, runtime).stream()
            .filter(item -> "Model".equals(item.group()))
            .toList();
        selectedCompletion = currentModelCompletionIndex(modelCompletions);
    }

    private static int currentModelCompletionIndex(List<CompletionItem> modelCompletions) {
        for (int i = 0; i < modelCompletions.size(); i++) {
            if ("当前模型".equals(modelCompletions.get(i).description())) {
                return i;
            }
        }
        return 0;
    }

    private static boolean isExactModelCommand(String value) {
        return value != null && value.trim().equalsIgnoreCase("/model");
    }

    private static boolean startsWithModelCommand(String value) {
        return value != null
            && value.length() >= "/model ".length()
            && value.regionMatches(true, 0, "/model ", 0, "/model ".length());
    }

    private void updateModelSelectorStateAfterEdit() {
        String value = input.toString();
        if (!isExactModelCommand(value) && !startsWithModelCommand(value)) {
            modelSelectorOpen = false;
        }
    }

    private void clearInput() {
        input.setLength(0);
        cursor = 0;
        selectedCompletion = 0;
        modelSelectorOpen = false;
        historyIndex = history.size();
    }

    private void backspace() {
        if (cursor <= 0) {
            return;
        }
        input.deleteCharAt(cursor - 1);
        cursor--;
        selectedCompletion = 0;
        updateModelSelectorStateAfterEdit();
    }

    private void deleteAtCursor() {
        if (cursor >= input.length()) {
            return;
        }
        input.deleteCharAt(cursor);
        selectedCompletion = 0;
        updateModelSelectorStateAfterEdit();
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
        modelSelectorOpen = false;
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
        return base.resolve(".bruce").resolve("history").toAbsolutePath().normalize();
    }

    int scrollOffset() {
        return scrollOffset;
    }

    String inputText() {
        return input.toString();
    }

    int selectedCompletion() {
        return selectedCompletion;
    }

    private BruceStatusInfo status(String phase) {
        return BruceStatusInfo.from(runtime, chatClient, phase);
    }

}
