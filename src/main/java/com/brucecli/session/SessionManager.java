package com.brucecli.session;

import com.brucecli.integrated.runtime.AgentMode;
import com.brucecli.llm.Message;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class SessionManager {
    private static final int CURRENT_VERSION = 1;
    private static final DateTimeFormatter SESSION_ID_TIME = DateTimeFormatter
        .ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC);

    private final ObjectMapper mapper = new ObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final Path homeDir;
    private Path workspaceRoot;
    private Path sessionDirectory;
    private Path sessionFile;
    private SessionHeader header;
    private final List<SessionEntry> entries = new ArrayList<>();
    private String activeLeafId;

    private SessionManager(Path homeDir, Path workspaceRoot) {
        this.homeDir = normalizeHome(homeDir);
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.sessionDirectory = sessionDirectory(this.homeDir, this.workspaceRoot);
    }

    public static SessionManager openLatestOrCreate(
        Path homeDir,
        Path workspaceRoot,
        AgentMode defaultMode
    ) {
        SessionManager manager = new SessionManager(homeDir, workspaceRoot);
        try {
            Optional<Path> latest = manager.latestSessionFile();
            if (latest.isPresent()) {
                manager.openFile(latest.get());
            } else {
                manager.createNew(defaultMode);
            }
            return manager;
        } catch (IOException e) {
            throw new IllegalStateException("Session 初始化失败: " + e.getMessage(), e);
        }
    }

    public synchronized SessionContext context(AgentMode fallbackMode) {
        return new SessionContext(
            header.id(),
            sessionFile,
            activeLeafId,
            currentMode(fallbackMode),
            messageCount(),
            buildMessages()
        );
    }

    public synchronized List<Message> buildMessages() {
        return activePath().stream()
            .map(this::messageForContext)
            .filter(message -> message != null && !"system".equals(message.role()))
            .toList();
    }

    public synchronized void appendMessage(Message message) throws IOException {
        if (message == null || "system".equals(message.role())) {
            return;
        }
        Message persisted = message.hasImageContent() ? message.withoutImageContent() : message;
        appendBranchEntry(SessionEntry.message(newEntryId(), activeLeafId, now(), persisted));
    }

    public synchronized void appendCustomEntry(String customType, Object data) throws IOException {
        appendBranchEntry(SessionEntry.custom(newEntryId(), activeLeafId, now(), customType, data));
    }

    public synchronized void appendCustomMessage(
        String customType,
        String content,
        boolean display,
        Object details
    ) throws IOException {
        appendBranchEntry(SessionEntry.customMessage(
            newEntryId(),
            activeLeafId,
            now(),
            customType,
            content == null ? "" : content,
            display,
            details
        ));
    }

    public synchronized void appendSessionInfo(String name) throws IOException {
        appendBranchEntry(SessionEntry.sessionInfo(newEntryId(), activeLeafId, now(), name));
    }

    public synchronized void appendModeChange(AgentMode mode) throws IOException {
        if (mode == null || currentMode(AgentMode.REACT) == mode) {
            return;
        }
        appendBranchEntry(SessionEntry.modeChange(newEntryId(), activeLeafId, now(), mode));
    }

    public synchronized void createNew(AgentMode mode) throws IOException {
        sessionDirectory = sessionDirectory(homeDir, workspaceRoot);
        Files.createDirectories(sessionDirectory);
        String id = newSessionId();
        sessionFile = sessionDirectory.resolve(id + ".jsonl");
        header = new SessionHeader(
            "session",
            CURRENT_VERSION,
            id,
            now(),
            workspaceRoot.toString(),
            null
        );
        entries.clear();
        activeLeafId = null;
        try (BufferedWriter writer = Files.newBufferedWriter(
            sessionFile,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
        )) {
            writer.write(mapper.writeValueAsString(header));
            writer.newLine();
            writer.flush();
        }
        if (mode != null && mode != AgentMode.REACT) {
            appendModeChange(mode);
        }
    }

    public synchronized void resume(String reference) throws IOException {
        openFile(resolveSessionFile(reference));
    }

    public synchronized void changeWorkspace(Path nextWorkspace, AgentMode fallbackMode) throws IOException {
        workspaceRoot = nextWorkspace.toAbsolutePath().normalize();
        sessionDirectory = sessionDirectory(homeDir, workspaceRoot);
        Optional<Path> latest = latestSessionFile();
        if (latest.isPresent()) {
            openFile(latest.get());
        } else {
            createNew(fallbackMode);
        }
    }

    public synchronized List<SessionSummary> listSessions(AgentMode fallbackMode) throws IOException {
        List<SessionSummary> summaries = new ArrayList<>();
        for (Path directory : sessionDirectories(homeDir, workspaceRoot)) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (var stream = Files.list(directory)) {
                List<Path> files = stream
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .toList();
                for (Path file : files) {
                    summaries.add(readSummary(file, fallbackMode));
                }
            }
        }
        summaries.sort(Comparator.comparing(SessionSummary::updatedAt).reversed());
        return summaries;
    }

    public synchronized void selectLeaf(String reference) throws IOException {
        String targetId = resolveEntryId(reference);
        appendNonBranchEntry(SessionEntry.leafChange(newEntryId(), activeLeafId, now(), targetId));
        activeLeafId = targetId;
    }

    public synchronized String renderTree(AgentMode fallbackMode) {
        List<SessionEntry> nodes = entries.stream()
            .filter(SessionEntry::isBranchNode)
            .toList();
        if (nodes.isEmpty()) {
            return "当前 session 还没有消息节点。";
        }
        Set<String> activePath = activePathIds();
        Map<String, List<SessionEntry>> children = new LinkedHashMap<>();
        for (SessionEntry node : nodes) {
            String parentId = node.parentId() == null ? "" : node.parentId();
            children.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(node);
        }
        StringBuilder output = new StringBuilder();
        output.append("Session: ").append(header.id()).append('\n');
        output.append("Mode: ").append(currentMode(fallbackMode)).append('\n');
        output.append("Active leaf: ").append(activeLeafId == null ? "-" : shortId(activeLeafId)).append('\n');
        renderChildren(output, children, "", 0, activePath);
        while (!output.isEmpty() && output.charAt(output.length() - 1) == '\n') {
            output.setLength(output.length() - 1);
        }
        return output.toString();
    }

    public synchronized String currentSessionId() {
        return header.id();
    }

    public synchronized Path currentFile() {
        return sessionFile;
    }

    public synchronized Path workspaceRoot() {
        return workspaceRoot;
    }

    public synchronized String activeLeafId() {
        return activeLeafId;
    }

    private void openFile(Path file) throws IOException {
        sessionFile = file.toAbsolutePath().normalize();
        List<String> lines = Files.readAllLines(sessionFile, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            throw new IOException("Session 文件为空: " + sessionFile);
        }
        header = mapper.readValue(lines.get(0), SessionHeader.class);
        if (!"session".equals(header.type())) {
            throw new IOException("不是有效 session 文件: " + sessionFile);
        }
        if (header.version() != CURRENT_VERSION) {
            throw new IOException("不支持的 session 版本: " + header.version());
        }
        entries.clear();
        activeLeafId = null;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }
            SessionEntry entry = mapper.readValue(line, SessionEntry.class);
            entries.add(entry);
            if (entry.isBranchNode()) {
                activeLeafId = entry.id();
            } else if ("leaf_change".equals(entry.type()) && entry.targetId() != null) {
                activeLeafId = entry.targetId();
            }
        }
        workspaceRoot = Path.of(header.cwd()).toAbsolutePath().normalize();
        sessionDirectory = sessionDirectory(homeDir, workspaceRoot);
    }

    private Optional<Path> latestSessionFile() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path directory : sessionDirectories(homeDir, workspaceRoot)) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (var stream = Files.list(directory)) {
                files.addAll(stream
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .toList());
            }
        }
        return files.stream().max(Comparator.comparing(this::lastModified));
    }

    private Path resolveSessionFile(String reference) throws IOException {
        if (reference == null || reference.isBlank()) {
            return latestSessionFile()
                .orElseThrow(() -> new IllegalArgumentException("没有可恢复的 session。"));
        }
        String value = reference.trim();
        Path candidate = Path.of(value);
        if (candidate.isAbsolute() || value.contains("/") || value.contains("\\")) {
            Path resolved = candidate.isAbsolute()
                ? candidate
                : workspaceRoot.resolve(candidate).normalize();
            if (Files.isRegularFile(resolved)) {
                return resolved.toAbsolutePath().normalize();
            }
        }
        List<SessionSummary> matches = listSessions(AgentMode.REACT).stream()
            .filter(summary -> summary.id().startsWith(value)
                || summary.file().getFileName().toString().startsWith(value))
            .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("未找到 session: " + reference);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("session 标识不唯一: " + reference);
        }
        return matches.get(0).file();
    }

    private SessionSummary readSummary(Path file, AgentMode fallbackMode) throws IOException {
        SessionManager reader = new SessionManager(homeDir, workspaceRoot);
        reader.openFile(file);
        return new SessionSummary(
            reader.header.id(),
            reader.sessionFile,
            reader.header.createdAt(),
            Files.getLastModifiedTime(file).toInstant(),
            reader.currentMode(fallbackMode),
            reader.activeLeafId,
            reader.messageCount()
        );
    }

    private void appendBranchEntry(SessionEntry entry) throws IOException {
        appendLine(entry);
        entries.add(entry);
        activeLeafId = entry.id();
    }

    private void appendNonBranchEntry(SessionEntry entry) throws IOException {
        appendLine(entry);
        entries.add(entry);
    }

    private Message messageForContext(SessionEntry entry) {
        if (entry == null) {
            return null;
        }
        if ("message".equals(entry.type())) {
            return entry.message();
        }
        if ("custom_message".equals(entry.type())) {
            return Message.user(entry.content());
        }
        return null;
    }

    private void appendLine(SessionEntry entry) throws IOException {
        Files.createDirectories(sessionFile.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(
            sessionFile,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        )) {
            writer.write(mapper.writeValueAsString(entry));
            writer.newLine();
            writer.flush();
        }
    }

    private List<SessionEntry> activePath() {
        Map<String, SessionEntry> byId = branchEntriesById();
        List<SessionEntry> path = new ArrayList<>();
        String cursor = activeLeafId;
        Set<String> seen = new HashSet<>();
        while (cursor != null && !cursor.isBlank() && seen.add(cursor)) {
            SessionEntry entry = byId.get(cursor);
            if (entry == null) {
                break;
            }
            path.add(0, entry);
            cursor = entry.parentId();
        }
        return path;
    }

    private Set<String> activePathIds() {
        Set<String> ids = new HashSet<>();
        for (SessionEntry entry : activePath()) {
            ids.add(entry.id());
        }
        return ids;
    }

    private AgentMode currentMode(AgentMode fallbackMode) {
        AgentMode mode = fallbackMode == null ? AgentMode.REACT : fallbackMode;
        for (SessionEntry entry : activePath()) {
            if ("mode_change".equals(entry.type()) && entry.mode() != null) {
                mode = entry.mode();
            }
        }
        return mode;
    }

    private int messageCount() {
        int count = 0;
        for (SessionEntry entry : entries) {
            if ("message".equals(entry.type())) {
                count++;
            }
        }
        return count;
    }

    private Map<String, SessionEntry> branchEntriesById() {
        Map<String, SessionEntry> byId = new HashMap<>();
        for (SessionEntry entry : entries) {
            if (entry.isBranchNode()) {
                byId.put(entry.id(), entry);
            }
        }
        return byId;
    }

    private String resolveEntryId(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("请提供 tree 节点 id。");
        }
        String value = reference.trim();
        List<SessionEntry> matches = entries.stream()
            .filter(SessionEntry::isBranchNode)
            .filter(entry -> entry.id().equals(value) || entry.id().startsWith(value))
            .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("未找到 tree 节点: " + reference);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("tree 节点 id 不唯一: " + reference);
        }
        return matches.get(0).id();
    }

    private void renderChildren(
        StringBuilder output,
        Map<String, List<SessionEntry>> children,
        String parentId,
        int depth,
        Set<String> activePath
    ) {
        for (SessionEntry child : children.getOrDefault(parentId, List.of())) {
            output.append("  ".repeat(depth));
            output.append(activeLeafId != null && activeLeafId.equals(child.id()) ? "* " : activePath.contains(child.id()) ? "> " : "- ");
            output.append(shortId(child.id())).append(' ');
            output.append(label(child)).append('\n');
            renderChildren(output, children, child.id(), depth + 1, activePath);
        }
    }

    private String label(SessionEntry entry) {
        if ("mode_change".equals(entry.type())) {
            return "mode " + entry.mode();
        }
        if ("custom".equals(entry.type())) {
            return "custom " + nullToEmpty(entry.customType());
        }
        if ("custom_message".equals(entry.type())) {
            return "custom_message " + labelContent(entry.content());
        }
        if ("session_info".equals(entry.type())) {
            return "session_info " + labelContent(entry.name());
        }
        Message message = entry.message();
        if (message == null) {
            return entry.type();
        }
        String content = labelContent(message.content());
        return message.role() + (content.isBlank() ? "" : " " + content);
    }

    private static String labelContent(String value) {
        String content = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (content.length() > 48) {
            content = content.substring(0, 45) + "...";
        }
        return content;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    private static Path normalizeHome(Path homeDir) {
        Path base = homeDir == null ? Path.of(System.getProperty("user.home")) : homeDir;
        return base.toAbsolutePath().normalize();
    }

    private static Path sessionDirectory(Path homeDir, Path workspaceRoot) {
        return homeDir.resolve(".brucecli")
            .resolve("sessions")
            .resolve(formatWorkspaceDirectory(workspaceRoot))
            .toAbsolutePath()
            .normalize();
    }

    private static List<Path> sessionDirectories(Path homeDir, Path workspaceRoot) {
        Path current = sessionDirectory(homeDir, workspaceRoot);
        Path legacy = legacySessionDirectory(homeDir, workspaceRoot);
        if (current.equals(legacy)) {
            return List.of(current);
        }
        return List.of(current, legacy);
    }

    private static Path legacySessionDirectory(Path homeDir, Path workspaceRoot) {
        return homeDir.resolve(".brucecli")
            .resolve("sessions")
            .resolve(legacyEncodeWorkspace(workspaceRoot))
            .toAbsolutePath()
            .normalize();
    }

    private static String formatWorkspaceDirectory(Path workspaceRoot) {
        String cwd = workspaceRoot.toAbsolutePath().normalize().toString();
        return "--" + cwd.replaceFirst("^[/\\\\]", "").replaceAll("[/\\\\:]", "-") + "--";
    }

    private static String legacyEncodeWorkspace(Path workspaceRoot) {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(workspaceRoot.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String newSessionId() {
        return SESSION_ID_TIME.format(Instant.now()) + "-" + shortRandomId();
    }

    private static String newEntryId() {
        return "e_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static String shortRandomId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static String shortId(String id) {
        if (id == null || id.length() <= 10) {
            return id;
        }
        return id.substring(0, 10);
    }

    private static String now() {
        return Instant.now().toString();
    }
}
