package com.brucecli.tool;

import java.util.List;
import java.util.Locale;

/**
 * 命令安全兜底：先快速剔除明显危险或会拖垮机器的命令。
 */
public class CommandGuard {
    private static final List<String> DANGEROUS_PATTERNS = List.of(
        "rm -rf /",
        "sudo ",
        "mkfs",
        "dd if=",
        ":(){",
        "chmod -r 777 /",
        "chown -r ",
        "diskutil erase"
    );

    public GuardResult check(String command) {
        if (command == null || command.isBlank()) {
            return GuardResult.deny("命令不能为空");
        }

        String normalized = command.trim().replaceAll("\\s+", " ");
        String lower = normalized.toLowerCase(Locale.ROOT);
        for (String pattern : DANGEROUS_PATTERNS) {
            if (lower.contains(pattern.toLowerCase(Locale.ROOT))) {
                return GuardResult.deny("命中危险命令黑名单: " + pattern);
            }
        }
        if (isFullDiskScan(lower)) {
            return GuardResult.deny("禁止全盘扫描或全盘遍历");
        }
        return GuardResult.allow();
    }

    private boolean isFullDiskScan(String lowerCommand) {
        return lowerCommand.matches(".*\\bfind\\s+/($|\\s.*)")
            || lowerCommand.matches(".*\\bgrep\\s+-[a-z]*r[a-z]*\\b.*\\s/($|\\s.*)")
            || lowerCommand.matches(".*\\bdu\\s+[^;&|]*\\s/($|\\s.*)")
            || lowerCommand.matches(".*\\bls\\s+-[a-z]*r[a-z]*\\s+/($|\\s.*)");
    }

    public record GuardResult(boolean allowed, String reason) {
        public static GuardResult allow() {
            return new GuardResult(true, "");
        }

        public static GuardResult deny(String reason) {
            return new GuardResult(false, reason);
        }
    }
}
