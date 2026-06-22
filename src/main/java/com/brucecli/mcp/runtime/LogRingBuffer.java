package com.brucecli.mcp.runtime;

import java.util.ArrayDeque;
import java.util.List;

public class LogRingBuffer {
    private final int limit;
    private final ArrayDeque<String> lines = new ArrayDeque<>();

    public LogRingBuffer(int limit) {
        this.limit = Math.max(1, limit);
    }

    public synchronized void add(String line) {
        if (line == null) {
            return;
        }
        while (lines.size() >= limit) {
            lines.removeFirst();
        }
        lines.addLast(line);
    }

    public synchronized List<String> lines() {
        return List.copyOf(lines);
    }

    public synchronized String text() {
        if (lines.isEmpty()) {
            return "暂无日志。";
        }
        return String.join("\n", lines);
    }
}
