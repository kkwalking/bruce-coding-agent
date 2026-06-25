package com.brucecli.integrated.cli;

import com.brucecli.integrated.runtime.IntegratedRuntime;
import com.brucecli.skill.SkillDefinition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class BruceCompletionEngine {
    private BruceCompletionEngine() {
    }

    public static List<CompletionItem> complete(String input, int cursor, IntegratedRuntime runtime) {
        String text = input == null ? "" : input;
        int safeCursor = Math.max(0, Math.min(cursor, text.length()));
        String prefixText = text.substring(0, safeCursor);
        String word = currentWord(prefixText);
        List<CompletionItem> candidates = new ArrayList<>();
        if (word.startsWith("@image:")) {
            completeImagePath(word, candidates);
            return candidates;
        }
        if (word.startsWith("$")) {
            completeExplicitSkill(word, runtime, candidates);
            return candidates;
        }
        if (prefixText.startsWith("/")) {
            completeSlash(prefixText, word, runtime, candidates);
        }
        return candidates;
    }

    static String applyCompletion(String input, int cursor, CompletionItem item) {
        String text = input == null ? "" : input;
        int safeCursor = Math.max(0, Math.min(cursor, text.length()));
        int start = wordStart(text, safeCursor);
        if (text.startsWith("/") && !text.substring(0, safeCursor).contains(" ")) {
            start = 0;
        }
        return text.substring(0, start) + item.value() + text.substring(safeCursor);
    }

    private static void completeSlash(String input, String word, IntegratedRuntime runtime, List<CompletionItem> candidates) {
        if (!input.contains(" ") && !input.endsWith(" ")) {
            addMatching(candidates, "bruce 命令", input, BruceSlashCommandHints.topLevel());
            return;
        }

        String payload = input.substring(1);
        String[] parts = payload.trim().isEmpty() ? new String[0] : payload.trim().split("\\s+");
        String command = parts.length == 0 ? "" : parts[0].toLowerCase();
        String prefix = input.endsWith(" ") ? "" : word;

        switch (command) {
            case "rag", "hitl", "parallel", "concurrency" ->
                addMatching(candidates, "状态", prefix, List.of(
                    option("on", "开启"),
                    option("off", "关闭"),
                    option("status", "查看状态")
                ));
            case "web" -> completeWeb(parts, prefix, input.endsWith(" "), candidates);
            case "mcp" -> completeMcp(parts, prefix, input.endsWith(" "), runtime, candidates);
            case "memory" -> completeMemory(parts, prefix, input.endsWith(" "), candidates);
            case "skill" -> completeSkill(parts, prefix, input.endsWith(" "), runtime, candidates);
            default -> {
                if (word.startsWith("@image:")) {
                    completeImagePath(word, candidates);
                }
            }
        }
    }

    private static void completeWeb(String[] parts, String prefix, boolean inputEndsWithSpace, List<CompletionItem> candidates) {
        if (parts.length <= 1 || (parts.length == 2 && !inputEndsWithSpace)) {
            addMatching(candidates, "Web", prefix, List.of(
                option("on", "开启 Web 工具"),
                option("off", "关闭 Web 工具"),
                option("status", "查看状态"),
                option("search ", "联网搜索"),
                option("fetch ", "抓取网页正文")
            ));
        }
    }

    private static void completeMcp(
        String[] parts,
        String prefix,
        boolean inputEndsWithSpace,
        IntegratedRuntime runtime,
        List<CompletionItem> candidates
    ) {
        if (parts.length <= 1 || (parts.length == 2 && !inputEndsWithSpace)) {
            addMatching(candidates, "MCP", prefix, List.of(
                option("status", "查看状态"),
                option("restart ", "重启 server"),
                option("logs ", "查看日志"),
                option("disable ", "禁用 server"),
                option("enable ", "启用 server")
            ));
            return;
        }
        String sub = parts[1].toLowerCase();
        if (List.of("restart", "logs", "disable", "enable").contains(sub)) {
            for (String name : runtime == null ? List.<String>of() : runtime.mcpServerNames()) {
                if (matches(name, prefix)) {
                    candidates.add(new CompletionItem(name, name, "已配置 server", "MCP server", true));
                }
            }
        }
    }

    private static void completeMemory(String[] parts, String prefix, boolean inputEndsWithSpace, List<CompletionItem> candidates) {
        if (parts.length <= 1 || (parts.length == 2 && !inputEndsWithSpace)) {
            addMatching(candidates, "Memory", prefix, List.of(
                option("status", "查看状态"),
                option("save ", "保存长期记忆"),
                option("search ", "搜索长期记忆")
            ));
        }
    }

    private static void completeSkill(
        String[] parts,
        String prefix,
        boolean inputEndsWithSpace,
        IntegratedRuntime runtime,
        List<CompletionItem> candidates
    ) {
        if (parts.length <= 1 || (parts.length == 2 && !inputEndsWithSpace)) {
            addMatching(candidates, "Skill", prefix, List.of(
                option("list", "列出 Skill"),
                option("show ", "查看 Skill"),
                option("reload", "重新扫描")
            ));
            return;
        }
        if ("show".equalsIgnoreCase(parts[1])) {
            completeSkillNames(prefix, runtime, candidates, false);
        }
    }

    private static void completeExplicitSkill(String word, IntegratedRuntime runtime, List<CompletionItem> candidates) {
        String prefix = word.length() <= 1 ? "" : word.substring(1);
        completeSkillNames(prefix, runtime, candidates, true);
    }

    private static void completeSkillNames(
        String prefix,
        IntegratedRuntime runtime,
        List<CompletionItem> candidates,
        boolean explicitSelector
    ) {
        for (SkillDefinition skill : runtime == null ? List.<SkillDefinition>of() : runtime.skills()) {
            String name = skill.name();
            if (!matches(name, prefix)) {
                continue;
            }
            String value = explicitSelector ? "$" + name + " " : name;
            candidates.add(new CompletionItem(value, value, skill.description(), "Skill", true));
        }
    }

    private static void completeImagePath(String word, List<CompletionItem> candidates) {
        String prefix = word.substring("@image:".length());
        boolean angle = prefix.startsWith("<");
        String pathPrefix = angle ? prefix.substring(1) : prefix;
        for (CompletionItem candidate : localPathCandidates(pathPrefix, "图片路径")) {
            String value = angle ? "@image:<" + candidate.value() : "@image:" + candidate.value();
            candidates.add(new CompletionItem(value, value, candidate.description(), candidate.group(), true));
        }
    }

    private static List<CompletionItem> localPathCandidates(String prefix, String group) {
        Path raw = prefix == null || prefix.isBlank() ? Path.of(".") : Path.of(prefix);
        Path directory = Files.isDirectory(raw) ? raw : raw.getParent();
        if (directory == null) {
            directory = Path.of(".");
        }
        String namePrefix = Files.isDirectory(raw) ? "" : raw.getFileName() == null ? "" : raw.getFileName().toString();
        List<CompletionItem> result = new ArrayList<>();
        Path listingDirectory = directory;
        String listingNamePrefix = namePrefix;
        try (var stream = Files.list(listingDirectory)) {
            stream.sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .limit(80)
                .forEach(path -> {
                    String fileName = path.getFileName().toString();
                    if (!matches(fileName, listingNamePrefix)) {
                        return;
                    }
                    boolean dir = Files.isDirectory(path);
                    Path valuePath = listingDirectory.equals(Path.of(".")) ? Path.of(fileName) : listingDirectory.resolve(fileName);
                    String value = valuePath.toString() + (dir ? "/" : "");
                    result.add(new CompletionItem(value, value, dir ? "目录" : "文件", group, !dir));
                });
        } catch (Exception ignored) {
            // Completion is best-effort and should never interrupt editing.
        }
        return result;
    }

    private static void addMatching(
        List<CompletionItem> candidates,
        String group,
        String prefix,
        List<BruceSlashCommandHints.SlashCommandHint> options
    ) {
        for (BruceSlashCommandHints.SlashCommandHint option : options) {
            if (matches(option.value(), prefix)) {
                candidates.add(new CompletionItem(
                    option.value(),
                    option.value(),
                    option.description(),
                    group,
                    option.complete()
                ));
            }
        }
    }

    private static boolean matches(String value, String prefix) {
        return prefix == null || prefix.isBlank() || value.toLowerCase().startsWith(prefix.toLowerCase());
    }

    private static BruceSlashCommandHints.SlashCommandHint option(String value, String description) {
        return new BruceSlashCommandHints.SlashCommandHint(value, description, true);
    }

    private static String currentWord(String input) {
        int start = wordStart(input, input.length());
        return input.substring(start);
    }

    private static int wordStart(String input, int cursor) {
        int start = Math.max(0, Math.min(cursor, input.length()));
        while (start > 0 && !Character.isWhitespace(input.charAt(start - 1))) {
            start--;
        }
        return start;
    }
}
