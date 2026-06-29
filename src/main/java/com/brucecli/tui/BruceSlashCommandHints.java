package com.brucecli.tui;

import java.util.List;

final class BruceSlashCommandHints {
    private static final List<SlashCommandHint> TOP_LEVEL = List.of(
        hint("/react", "切换到 ReAct 模式"),
        hint("/plan", "切换到 Plan-and-Execute 模式"),
        hint("/multi", "切换到 Multi-Agent 模式"),
        hint("/rag ", "RAG 开关和状态"),
        hint("/index ", "建立代码索引"),
        hint("/search ", "搜索代码索引"),
        hint("/graph ", "查看图谱"),
        hint("/web ", "Web 开关和搜索"),
        hint("/mcp ", "MCP server 管理"),
        hint("/memory ", "长期记忆"),
        hint("/hitl ", "HITL 开关和状态"),
        hint("/parallel ", "并行执行开关和状态"),
        hint("/skill ", "Skill 管理"),
        hint("/status", "查看统一运行状态"),
        hint("/session", "查看当前 session"),
        hint("/sessions", "列出最近 session"),
        hint("/new", "新建 session"),
        hint("/resume ", "恢复 session"),
        hint("/tree ", "查看或切换 session 树"),
        hint("/clear", "开启新 session"),
        hint("/help", "查看帮助"),
        hint("/exit", "退出 bruce")
    );

    private BruceSlashCommandHints() {
    }

    static List<SlashCommandHint> topLevel() {
        return TOP_LEVEL;
    }

    private static SlashCommandHint hint(String value, String description) {
        return new SlashCommandHint(value, description, true);
    }

    record SlashCommandHint(String value, String description, boolean complete) {
    }
}
