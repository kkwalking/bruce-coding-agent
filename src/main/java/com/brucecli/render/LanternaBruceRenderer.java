package com.brucecli.render;

import com.brucecli.approval.ApprovalRequest;
import com.brucecli.approval.ApprovalResult;
import com.brucecli.integrated.cli.BruceSyntaxHighlighter;
import com.brucecli.integrated.cli.CompletionItem;
import com.brucecli.integrated.cli.StyledSpan;
import com.brucecli.rag.model.IndexProgress;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TerminalTextUtils;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.graphics.TextGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class LanternaBruceRenderer implements BruceRenderer {
    private static final long INDEX_PROGRESS_RENDER_INTERVAL_NANOS = 100_000_000L;
    private static final TextColor.ANSI BASE = TextColor.ANSI.DEFAULT;
    private static final TextColor.ANSI DIM = TextColor.ANSI.WHITE;
    private static final TextColor.ANSI BRAND = TextColor.ANSI.YELLOW_BRIGHT;
    private static final TextColor.ANSI OK = TextColor.ANSI.GREEN_BRIGHT;
    private static final TextColor.ANSI WARN = TextColor.ANSI.RED_BRIGHT;
    private static final TextColor.ANSI INFO = TextColor.ANSI.CYAN_BRIGHT;
    private static final TextColor.ANSI USER = TextColor.ANSI.GREEN_BRIGHT;

    private final Screen screen;
    private final Object lock = new Object();
    private final List<TuiMessage> messages = new ArrayList<>();
    private final PrintStream stream;
    private final AtomicBoolean dirty = new AtomicBoolean(true);
    private BruceStatusInfo currentStatus;
    private IndexProgress indexProgress;
    private ApprovalDialog approvalDialog;
    private int lastColumns = -1;
    private int lastRows = -1;
    private long lastIndexProgressRenderNanos;
    private int streamingAssistantIndex = -1;
    private final StringBuilder streamingAssistant = new StringBuilder();
    private boolean closed;

    public LanternaBruceRenderer(Screen screen) {
        this.screen = screen;
        this.stream = new PrintStream(new RendererOutputStream(), true, StandardCharsets.UTF_8);
    }

    @Override
    public void start() {
    }

    @Override
    public void renderWelcome(BruceStatusInfo status) {
        currentStatus = status;
        appendRaw(welcomeLines(status));
        updateStatus(status);
    }

    @Override
    public void beforeInput(BruceStatusInfo status) {
        updateStatus(status);
    }

    @Override
    public void afterInput(BruceStatusInfo status) {
        updateStatus(status);
    }

    @Override
    public void appendUserMessage(String message) {
        append(MessageKind.USER, "❯ " + nullToEmpty(message));
    }

    @Override
    public void appendAssistantMessage(String message) {
        append(MessageKind.ASSISTANT, nullToEmpty(message));
    }

    @Override
    public void appendSystemMessage(String message) {
        append(MessageKind.SYSTEM, "• " + nullToEmpty(message));
    }

    @Override
    public void appendActivity(String message) {
        append(MessageKind.ACTIVITY, "* " + nullToEmpty(message));
    }

    @Override
    public void beginStreamingAssistantMessage() {
        if (closed) {
            return;
        }
        synchronized (lock) {
            if (streamingAssistantIndex >= 0) {
                return;
            }
            streamingAssistant.setLength(0);
            messages.add(new TuiMessage(MessageKind.ASSISTANT, ""));
            streamingAssistantIndex = messages.size() - 1;
        }
        markDirty();
    }

    @Override
    public void appendStreamingAssistantDelta(String delta) {
        if (closed || delta == null || delta.isEmpty()) {
            return;
        }
        synchronized (lock) {
            if (streamingAssistantIndex < 0) {
                streamingAssistant.setLength(0);
                messages.add(new TuiMessage(MessageKind.ASSISTANT, ""));
                streamingAssistantIndex = messages.size() - 1;
            }
            streamingAssistant.append(delta);
            messages.set(streamingAssistantIndex, new TuiMessage(MessageKind.ASSISTANT, streamingAssistant.toString()));
        }
        markDirty();
    }

    @Override
    public void finishStreamingAssistantMessage(String finalText) {
        if (closed) {
            return;
        }
        String text = nullToEmpty(finalText);
        synchronized (lock) {
            if (streamingAssistantIndex < 0) {
                if (!text.isBlank()) {
                    messages.add(new TuiMessage(MessageKind.ASSISTANT, text.strip()));
                }
                markDirty();
                return;
            }
            if (text.isBlank() && streamingAssistant.isEmpty()) {
                messages.remove(streamingAssistantIndex);
            } else {
                String finalValue = text.isBlank() ? streamingAssistant.toString() : text;
                messages.set(streamingAssistantIndex, new TuiMessage(MessageKind.ASSISTANT, finalValue.strip()));
            }
            streamingAssistantIndex = -1;
            streamingAssistant.setLength(0);
        }
        markDirty();
    }

    @Override
    public void updateStatus(BruceStatusInfo status) {
        if (status != null) {
            synchronized (lock) {
                currentStatus = status;
            }
            markDirty();
        }
    }

    @Override
    public void updateIndexProgress(IndexProgress progress) {
        boolean shouldRender;
        synchronized (lock) {
            indexProgress = progress;
            shouldRender = shouldRenderIndexProgress(progress);
            if (shouldRender) {
                lastIndexProgressRenderNanos = System.nanoTime();
            }
        }
        if (shouldRender) {
            markDirty();
        }
    }

    @Override
    public String inputPrompt() {
        return "❯ ";
    }

    @Override
    public PrintStream stream() {
        return stream;
    }

    public void render(
        String input,
        int cursor,
        List<CompletionItem> completions,
        int selectedCompletion,
        int scrollOffset,
        boolean busy
    ) throws IOException {
        TerminalSize size = screen.doResizeIfNecessary();
        if (size == null) {
            size = screen.getTerminalSize();
        }
        int columns = Math.max(20, size.getColumns());
        int rows = Math.max(8, size.getRows());
        int statusRow = rows - 1;
        int inputBottom = rows - 2;
        int inputLine = rows - 3;
        int inputTop = rows - 4;
        int indexStatusRow = rows - 5;
        int messageRows = Math.max(1, indexStatusRow);

        TextGraphics graphics = screen.newTextGraphics();
        prepareCanvas(graphics, columns, rows);
        drawMessages(graphics, columns, messageRows, scrollOffset);
        drawCompletions(graphics, columns, indexStatusRow, completions, selectedCompletion);
        drawIndexProgress(graphics, columns, indexStatusRow);
        drawInput(graphics, columns, inputTop, inputLine, inputBottom, input, cursor, busy);
        drawStatus(graphics, columns, statusRow);
        drawApproval(graphics, columns, rows);
        screen.refresh();
    }

    public ApprovalResult requestApproval(ApprovalRequest request) {
        ApprovalDialog dialog = new ApprovalDialog(request);
        synchronized (lock) {
            approvalDialog = dialog;
        }
        markDirty();
        appendActivity("HITL 等待审批: " + request.toolName());
        return dialog.future.join();
    }

    public boolean handleApprovalKey(KeyStroke key) {
        ApprovalDialog dialog;
        synchronized (lock) {
            dialog = approvalDialog;
        }
        if (dialog == null || key == null) {
            return false;
        }

        KeyType type = key.getKeyType();
        Character ch = key.getCharacter();
        if (type == KeyType.Escape) {
            completeApproval(dialog, ApprovalResult.reject("用户取消审批"));
            return true;
        }
        if (type == KeyType.Backspace && dialog.editingText.length() > 0) {
            dialog.editingText.deleteCharAt(dialog.editingText.length() - 1);
            markDirty();
            return true;
        }
        if (type == KeyType.Enter) {
            if (dialog.mode == ApprovalMode.REJECT_REASON) {
                completeApproval(dialog, ApprovalResult.reject(
                    dialog.editingText.isEmpty() ? "用户拒绝了此操作" : dialog.editingText.toString()
                ));
                return true;
            }
            if (dialog.mode == ApprovalMode.MODIFY_ARGS) {
                String modified = dialog.editingText.toString().trim();
                if (!modified.isBlank()) {
                    completeApproval(dialog, ApprovalResult.modify(modified));
                }
                return true;
            }
            completeApproval(dialog, ApprovalResult.approve());
            return true;
        }
        if (type == KeyType.Character && ch != null) {
            char c = Character.toLowerCase(ch);
            if (dialog.mode == ApprovalMode.REJECT_REASON || dialog.mode == ApprovalMode.MODIFY_ARGS) {
                dialog.editingText.append(ch);
                markDirty();
                return true;
            }
            switch (c) {
                case 'y' -> completeApproval(dialog, ApprovalResult.approve());
                case 'a' -> completeApproval(dialog, ApprovalResult.approveAll());
                case 'n' -> {
                    dialog.mode = ApprovalMode.REJECT_REASON;
                    dialog.editingText.setLength(0);
                    markDirty();
                }
                case 's' -> completeApproval(dialog, ApprovalResult.skip());
                case 'm' -> {
                    dialog.mode = ApprovalMode.MODIFY_ARGS;
                    dialog.editingText.setLength(0);
                    markDirty();
                }
                default -> {
                    return true;
                }
            }
            return true;
        }
        return true;
    }

    public boolean hasApprovalDialog() {
        synchronized (lock) {
            return approvalDialog != null;
        }
    }

    public List<String> messageTexts() {
        synchronized (lock) {
            return messages.stream().map(TuiMessage::text).toList();
        }
    }

    public boolean consumeDirty() {
        return dirty.getAndSet(false);
    }

    public void markDirty() {
        dirty.set(true);
    }

    public int clampScrollOffset(int scrollOffset) {
        return clampScrollOffset(scrollOffset, maxScrollOffset());
    }

    public int maxScrollOffset() {
        TerminalSize size = screen.getTerminalSize();
        int columns = Math.max(20, size.getColumns());
        int rows = Math.max(8, size.getRows());
        int messageRows = Math.max(1, rows - 5);
        return maxScrollOffset(columns, messageRows);
    }

    public static TuiLayout layout(TerminalSize size) {
        int rows = Math.max(8, size.getRows());
        return new TuiLayout(Math.max(1, rows - 5), rows - 5, rows - 4, rows - 3, rows - 2, rows - 1);
    }

    @Override
    public void close() {
        closed = true;
        stream.flush();
    }

    private void completeApproval(ApprovalDialog dialog, ApprovalResult result) {
        synchronized (lock) {
            if (approvalDialog == dialog) {
                approvalDialog = null;
            }
        }
        markDirty();
        dialog.future.complete(result);
    }

    private boolean shouldRenderIndexProgress(IndexProgress progress) {
        if (progress == null) {
            return true;
        }
        if (!"indexing".equals(progress.phase())) {
            return true;
        }
        long now = System.nanoTime();
        return lastIndexProgressRenderNanos == 0
            || now - lastIndexProgressRenderNanos >= INDEX_PROGRESS_RENDER_INTERVAL_NANOS;
    }

    private void prepareCanvas(TextGraphics graphics, int columns, int rows) {
        if (columns != lastColumns || rows != lastRows) {
            screen.clear();
            lastColumns = columns;
            lastRows = rows;
            return;
        }
        style(graphics, BASE, false);
        String blank = " ".repeat(Math.max(1, columns));
        for (int row = 0; row < rows; row++) {
            graphics.putString(0, row, blank);
        }
    }

    private void appendRaw(List<String> lines) {
        append(MessageKind.SYSTEM, String.join(System.lineSeparator(), lines));
    }

    private void append(MessageKind kind, String text) {
        if (closed || text == null || text.isBlank()) {
            return;
        }
        synchronized (lock) {
            messages.add(new TuiMessage(kind, text.strip()));
        }
        markDirty();
    }

    private void drawMessages(TextGraphics graphics, int columns, int messageRows, int scrollOffset) {
        List<RenderLine> lines = visibleMessageLines(columns, messageRows, scrollOffset);
        int row = 0;
        for (RenderLine line : lines) {
            style(graphics, line.color(), line.bold());
            graphics.putString(0, row, fit(line.text(), columns));
            row++;
        }
    }

    int maxScrollOffset(int columns, int messageRows) {
        return maxScrollOffsetForLines(wrappedMessageLines(columns).size(), messageRows);
    }

    List<String> visibleMessageLineTexts(int columns, int messageRows, int scrollOffset) {
        return visibleMessageLines(columns, messageRows, scrollOffset).stream()
            .map(RenderLine::text)
            .toList();
    }

    private List<RenderLine> visibleMessageLines(int columns, int messageRows, int scrollOffset) {
        List<RenderLine> lines = wrappedMessageLines(columns);
        int clampedOffset = clampScrollOffset(scrollOffset, maxScrollOffsetForLines(lines.size(), messageRows));
        int start = Math.max(0, lines.size() - messageRows - clampedOffset);
        int end = Math.min(lines.size(), start + Math.max(0, messageRows));
        return lines.subList(start, end);
    }

    private List<RenderLine> wrappedMessageLines(int columns) {
        List<TuiMessage> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(messages);
        }
        List<RenderLine> result = new ArrayList<>();
        for (TuiMessage message : snapshot) {
            TextColor color = colorFor(message.kind());
            boolean bold = message.kind() == MessageKind.USER;
            for (String raw : message.text().split("\\R", -1)) {
                for (String line : wrap(raw, Math.max(1, columns))) {
                    result.add(new RenderLine(line, color, bold));
                }
            }
            result.add(new RenderLine("", BASE, false));
        }
        if (!result.isEmpty() && result.get(result.size() - 1).text().isBlank()) {
            result.remove(result.size() - 1);
        }
        return result;
    }

    private void drawCompletions(
        TextGraphics graphics,
        int columns,
        int indexStatusRow,
        List<CompletionItem> completions,
        int selectedCompletion
    ) {
        if (completions == null || completions.isEmpty() || indexStatusRow <= 1) {
            return;
        }
        int visible = Math.min(6, completions.size());
        int top = Math.max(0, indexStatusRow - visible - 1);
        int width = Math.min(columns, 72);
        style(graphics, DIM, false);
        graphics.putString(0, top, fit("┌" + "─".repeat(Math.max(0, width - 2)) + "┐", columns));
        for (int i = 0; i < visible && top + i + 1 < indexStatusRow; i++) {
            CompletionItem item = completions.get(i);
            boolean selected = i == selectedCompletion;
            style(graphics, selected ? TextColor.ANSI.BLACK : INFO, selected);
            if (selected) {
                graphics.setBackgroundColor(TextColor.ANSI.CYAN_BRIGHT);
            }
            String body = " " + item.display() + "  " + item.description();
            graphics.putString(0, top + i + 1, fit("│" + padRight(body, Math.max(0, width - 2)) + "│", columns));
            graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        }
    }

    private void drawInput(
        TextGraphics graphics,
        int columns,
        int inputTop,
        int inputLine,
        int inputBottom,
        String input,
        int cursor,
        boolean busy
    ) {
        style(graphics, DIM, false);
        graphics.putString(0, inputTop, inputFrameLine(columns));
        graphics.putString(0, inputBottom, inputFrameLine(columns));
        style(graphics, busy ? WARN : USER, true);
        String prompt = "❯ ";
        int promptWidth = columnWidth(prompt);
        String value = input == null ? "" : input;
        graphics.putString(0, inputLine, prompt);
        drawHighlightedInput(graphics, promptWidth, inputLine, columns - promptWidth, value);
        int cursorColumn = promptWidth + columnWidth(value.substring(0, Math.min(cursor, value.length())));
        screen.setCursorPosition(new TerminalPosition(Math.min(columns - 1, Math.max(promptWidth, cursorColumn)), inputLine));
    }

    private void drawHighlightedInput(TextGraphics graphics, int col, int row, int maxWidth, String input) {
        int x = col;
        for (StyledSpan span : BruceSyntaxHighlighter.highlight(input)) {
            style(graphics, colorFor(span.style()), span.style() == BruceSyntaxHighlighter.Style.COMMAND);
            String text = fit(span.text(), Math.max(0, maxWidth - (x - col)));
            if (!text.isEmpty()) {
                graphics.putString(x, row, text);
                x += columnWidth(text);
            }
            if (x - col >= maxWidth) {
                break;
            }
        }
    }

    private void drawIndexProgress(TextGraphics graphics, int columns, int row) {
        IndexProgress progress;
        synchronized (lock) {
            progress = indexProgress;
        }
        if (progress == null || row < 0) {
            return;
        }
        style(graphics, INFO, false);
        graphics.putString(0, row, fit(indexProgressText(progress), columns));
    }

    String indexProgressText() {
        synchronized (lock) {
            return indexProgress == null ? "" : indexProgressText(indexProgress);
        }
    }

    private static String indexProgressText(IndexProgress progress) {
        String currentFile = progress.currentFile().isBlank() ? progress.project() : progress.currentFile();
        String warnings = progress.warningCount() > 0 ? " · warnings=" + progress.warningCount() : "";
        return "RAG index: %d/%d files · chunks=%d · relations=%d%s · %s".formatted(
            progress.processedFiles(),
            progress.totalFiles(),
            progress.chunks(),
            progress.relations(),
            warnings,
            currentFile
        );
    }

    private void drawStatus(TextGraphics graphics, int columns, int row) {
        BruceStatusInfo status;
        synchronized (lock) {
            status = currentStatus;
        }
        if (status == null) {
            return;
        }
        style(graphics, status.hitlEnabled() ? OK : WARN, true);
        String permission = status.hitlEnabled() ? " HITL on " : " bypass permissions on ";
        graphics.putString(0, row, fit(permission, columns));
        style(graphics, DIM, false);
        String details = " bruce · " + status.model()
            + " · mode " + status.mode()
            + " · " + compactPath(status.workspace())
            + " · mcp " + status.mcpSummary()
            + " · skills " + status.skillCount()
            + " · memory " + status.memoryTokens() + "t";
        graphics.putString(Math.min(columns - 1, permission.length()), row, fit(details, Math.max(0, columns - permission.length())));
    }

    private void drawApproval(TextGraphics graphics, int columns, int rows) {
        ApprovalDialog dialog;
        synchronized (lock) {
            dialog = approvalDialog;
        }
        if (dialog == null) {
            return;
        }
        int width = Math.min(columns - 4, 76);
        int height = Math.min(rows - 4, 16);
        int left = Math.max(0, (columns - width) / 2);
        int top = Math.max(0, (rows - height) / 2);
        style(graphics, TextColor.ANSI.WHITE_BRIGHT, false);
        graphics.setBackgroundColor(TextColor.ANSI.BLACK);
        for (int y = 0; y < height; y++) {
            graphics.putString(left, top + y, " ".repeat(width));
        }
        style(graphics, WARN, true);
        graphics.putString(left, top, fit("┌ HITL 审批 " + "─".repeat(Math.max(0, width - 12)) + "┐", width));
        style(graphics, BASE, false);
        List<String> lines = approvalLines(dialog);
        for (int i = 0; i < lines.size() && i < height - 2; i++) {
            graphics.putString(left, top + i + 1, fit("│ " + padRight(lines.get(i), width - 4) + " │", width));
        }
        style(graphics, WARN, true);
        graphics.putString(left, top + height - 1, fit("└" + "─".repeat(Math.max(0, width - 2)) + "┘", width));
        graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
    }

    private List<String> approvalLines(ApprovalDialog dialog) {
        ApprovalRequest request = dialog.request;
        List<String> lines = new ArrayList<>();
        lines.add("工具: " + request.toolName());
        lines.add("等级: " + request.dangerLevel());
        lines.add("风险: " + request.riskDescription());
        if (request.suggestion() != null && !request.suggestion().isBlank()) {
            lines.add("建议: " + request.suggestion().trim());
        }
        lines.add("参数: " + request.arguments());
        lines.add("");
        if (dialog.mode == ApprovalMode.REJECT_REASON) {
            lines.add("拒绝原因: " + dialog.editingText);
        } else if (dialog.mode == ApprovalMode.MODIFY_ARGS) {
            lines.add("修改参数 JSON: " + dialog.editingText);
        } else {
            lines.add("[Enter/y] 批准  [a] 全部放行  [n] 拒绝  [s] 跳过  [m] 修改");
        }
        return lines;
    }

    private static List<String> welcomeLines(BruceStatusInfo status) {
        String model = status == null ? "auto" : status.model();
        String workspace = status == null ? "" : compactPath(status.workspace());
        return List.of(
            "┌─ Bruce CLI ─────────────────────────────────────────────────────────┐",
            "│  Welcome back        │ Tips for getting started                    │",
            "│  bruce               │ Run /help to see bruce commands             │",
            "│  " + padRight(model, 18) + "│ What's new                                  │",
            "│  " + padRight(workspace, 18) + "│ /skill lists project skills and /mcp shows tools│",
            "│                      │ /rag on enables code search after /index    │",
            "└────────────────────────────────────────────────────────────────────┘"
        );
    }

    static String inputFrameLine(int columns) {
        return "━".repeat(Math.max(1, columns));
    }

    private static void style(TextGraphics graphics, TextColor color, boolean bold) {
        graphics.setForegroundColor(color);
        graphics.setBackgroundColor(TextColor.ANSI.DEFAULT);
        graphics.clearModifiers();
        if (bold) {
            graphics.enableModifiers(SGR.BOLD);
        }
    }

    private static TextColor colorFor(MessageKind kind) {
        return switch (kind) {
            case USER -> USER;
            case SYSTEM -> INFO;
            case ACTIVITY -> DIM;
            case ASSISTANT -> BASE;
        };
    }

    private static TextColor colorFor(BruceSyntaxHighlighter.Style style) {
        return switch (style) {
            case COMMAND -> INFO;
            case MENTION -> TextColor.ANSI.BLUE_BRIGHT;
            case IMAGE -> TextColor.ANSI.MAGENTA_BRIGHT;
            case DANGER -> WARN;
            case SECRET -> BRAND;
            case NORMAL -> BASE;
        };
    }

    private static List<String> wrap(String text, int width) {
        String value = text == null ? "" : text;
        if (width <= 0 || columnWidth(value) <= width) {
            return List.of(value);
        }
        List<String> result = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int columns = 0;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            String character = new String(Character.toChars(codePoint));
            int characterWidth = Math.max(0, columnWidth(character));
            if (!line.isEmpty() && columns + characterWidth > width) {
                result.add(line.toString());
                line.setLength(0);
                columns = 0;
            }
            line.append(character);
            columns += characterWidth;
            index += Character.charCount(codePoint);
        }
        if (!line.isEmpty()) {
            result.add(line.toString());
        }
        return result;
    }

    private static String fit(String value, int width) {
        String text = value == null ? "" : value;
        if (width <= 0) {
            return "";
        }
        if (columnWidth(text) <= width) {
            return text;
        }
        int maxBodyWidth = Math.max(0, width - columnWidth("…"));
        StringBuilder builder = new StringBuilder();
        int columns = 0;
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            String character = new String(Character.toChars(codePoint));
            int characterWidth = Math.max(0, columnWidth(character));
            if (columns + characterWidth > maxBodyWidth) {
                break;
            }
            builder.append(character);
            columns += characterWidth;
            index += Character.charCount(codePoint);
        }
        return builder.append("…").toString();
    }

    private static String padRight(String value, int width) {
        String text = fit(value == null ? "" : value, width);
        return fit(text + " ".repeat(Math.max(0, width - columnWidth(text))), width);
    }

    private static int clampScrollOffset(int scrollOffset, int maxScrollOffset) {
        return Math.max(0, Math.min(Math.max(0, scrollOffset), Math.max(0, maxScrollOffset)));
    }

    private static int maxScrollOffsetForLines(int lineCount, int messageRows) {
        return Math.max(0, lineCount - Math.max(1, messageRows));
    }

    static int displayColumnWidth(String value) {
        return columnWidth(value);
    }

    private static int columnWidth(String value) {
        return TerminalTextUtils.getColumnWidth(value == null ? "" : value);
    }

    private static String compactPath(Path path) {
        if (path == null) {
            return "";
        }
        Path fileName = path.getFileName();
        return fileName == null ? path.toString() : "~/" + fileName;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private enum MessageKind {
        USER,
        ASSISTANT,
        SYSTEM,
        ACTIVITY
    }

    private record TuiMessage(MessageKind kind, String text) {
    }

    private record RenderLine(String text, TextColor color, boolean bold) {
    }

    public record TuiLayout(int messageRows, int indexStatusRow, int inputTop, int inputLine, int inputBottom, int statusRow) {
    }

    private enum ApprovalMode {
        CHOOSE,
        REJECT_REASON,
        MODIFY_ARGS
    }

    private static final class ApprovalDialog {
        private final ApprovalRequest request;
        private final CompletableFuture<ApprovalResult> future = new CompletableFuture<>();
        private final StringBuilder editingText = new StringBuilder();
        private ApprovalMode mode = ApprovalMode.CHOOSE;

        private ApprovalDialog(ApprovalRequest request) {
            this.request = request;
        }
    }

    private final class RendererOutputStream extends OutputStream {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        @Override
        public synchronized void write(int b) {
            if (b == '\n') {
                flushBuffer();
                return;
            }
            if (b != '\r') {
                buffer.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            for (int index = offset; index < offset + length; index++) {
                write(bytes[index]);
            }
        }

        @Override
        public synchronized void flush() {
            flushBuffer();
        }

        private void flushBuffer() {
            if (buffer.size() == 0) {
                return;
            }
            String line = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            buffer.reset();
            appendActivity(line);
        }
    }
}
