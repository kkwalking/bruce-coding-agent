package com.brucecli.instructions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AgentInstructionsLoader {
    public static final int MAX_PROMPT_BYTES = 32_768;

    public AgentInstructionsLoadResult load(Path userHome, Path workspaceRoot) {
        Path normalizedHome = normalize(userHome);
        Path normalizedWorkspace = normalize(workspaceRoot);
        List<String> diagnostics = new ArrayList<>();
        BoundedPromptBuilder builder = new BoundedPromptBuilder(MAX_PROMPT_BYTES);

        appendFile(normalizedHome.resolve(".bruce").resolve("AGENTS.md"), builder, diagnostics);
        for (Path directory : projectInstructionDirectories(normalizedWorkspace)) {
            if (builder.isFull()) {
                break;
            }
            appendFile(directory.resolve("AGENTS.md"), builder, diagnostics);
        }

        return new AgentInstructionsLoadResult(builder.toString(), diagnostics);
    }

    private Path normalize(Path path) {
        Path fallback = Path.of(System.getProperty("user.home"));
        return (path == null ? fallback : path).toAbsolutePath().normalize();
    }

    private List<Path> projectInstructionDirectories(Path workspaceRoot) {
        Path gitRoot = findGitRoot(workspaceRoot);
        if (gitRoot == null) {
            return List.of(workspaceRoot);
        }

        List<Path> directories = new ArrayList<>();
        Path current = workspaceRoot;
        while (current != null) {
            directories.add(current);
            if (current.equals(gitRoot)) {
                break;
            }
            current = current.getParent();
        }
        Collections.reverse(directories);
        return directories;
    }

    private Path findGitRoot(Path workspaceRoot) {
        Path current = workspaceRoot;
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private void appendFile(Path file, BoundedPromptBuilder builder, List<String> diagnostics) {
        if (builder.isFull() || !Files.isRegularFile(file)) {
            return;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8).strip();
            if (!content.isBlank()) {
                builder.append(content);
            }
        } catch (IOException e) {
            diagnostics.add(file + ": AGENTS.md 读取失败: " + e.getMessage());
        }
    }

    private static class BoundedPromptBuilder {
        private final int maxBytes;
        private final StringBuilder builder = new StringBuilder();
        private int bytes;
        private boolean full;

        BoundedPromptBuilder(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        void append(String content) {
            if (full || content == null || content.isBlank()) {
                return;
            }
            String separator = builder.length() == 0 ? "" : "\n\n";
            int separatorBytes = utf8Bytes(separator);
            int remaining = maxBytes - bytes - separatorBytes;
            if (remaining <= 0) {
                full = true;
                return;
            }

            int contentBytes = utf8Bytes(content);
            String appended = contentBytes <= remaining
                ? content
                : truncateUtf8(content, remaining);
            if (appended.isEmpty()) {
                full = true;
                return;
            }

            builder.append(separator).append(appended);
            bytes += separatorBytes + utf8Bytes(appended);
            if (contentBytes > remaining || bytes >= maxBytes) {
                full = true;
            }
        }

        boolean isFull() {
            return full;
        }

        @Override
        public String toString() {
            return builder.toString();
        }

        private static int utf8Bytes(String value) {
            return value.getBytes(StandardCharsets.UTF_8).length;
        }

        private static String truncateUtf8(String value, int maxBytes) {
            StringBuilder result = new StringBuilder();
            int bytes = 0;
            for (int i = 0; i < value.length();) {
                int codePoint = value.codePointAt(i);
                String next = new String(Character.toChars(codePoint));
                int nextBytes = utf8Bytes(next);
                if (bytes + nextBytes > maxBytes) {
                    break;
                }
                result.append(next);
                bytes += nextBytes;
                i += Character.charCount(codePoint);
            }
            return result.toString();
        }
    }
}
