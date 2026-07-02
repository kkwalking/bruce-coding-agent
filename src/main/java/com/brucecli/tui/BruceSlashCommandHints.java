package com.brucecli.tui;

import com.brucecli.integrated.cli.IntegratedCommandProcessor;

import java.util.ArrayList;
import java.util.List;

final class BruceSlashCommandHints {
    private static final List<SlashCommandHint> TOP_LEVEL = createTopLevel(false);
    private static final List<SlashCommandHint> TOP_LEVEL_WITH_RAG = createTopLevel(true);

    private BruceSlashCommandHints() {
    }

    static List<SlashCommandHint> topLevel() {
        return IntegratedCommandProcessor.ragSlashCommandsEnabled() ? TOP_LEVEL_WITH_RAG : TOP_LEVEL;
    }

    private static List<SlashCommandHint> createTopLevel(boolean includeRag) {
        List<SlashCommandHint> hints = new ArrayList<>();
        hints.add(hint("/react", "切换到 ReAct 模式"));
        hints.add(hint("/plan", "切换到 Plan-and-Execute 模式"));
        hints.add(hint("/model", "选择模型"));
        if (includeRag) {
            hints.add(hint("/rag ", "RAG 开关和状态"));
            hints.add(hint("/index ", "建立代码索引"));
            hints.add(hint("/search ", "搜索代码索引"));
            hints.add(hint("/graph ", "查看图谱"));
        }
        hints.add(hint("/web ", "Web 开关和搜索"));
        hints.add(hint("/mcp ", "MCP server 管理"));
        hints.add(hint("/hitl ", "HITL 开关和状态"));
        hints.add(hint("/parallel ", "并行执行开关和状态"));
        hints.add(hint("/skill ", "Skill 管理"));
        hints.add(hint("/status", "查看统一运行状态"));
        hints.add(hint("/session", "查看当前 session"));
        hints.add(hint("/sessions", "列出最近 session"));
        hints.add(hint("/new", "新建 session"));
        hints.add(hint("/resume ", "恢复 session"));
        hints.add(hint("/tree ", "查看或切换 session 树"));
        hints.add(hint("/compact ", "压缩较早会话历史"));
        hints.add(hint("/clear", "开启新 session"));
        hints.add(hint("/help", "查看帮助"));
        hints.add(hint("/exit", "退出 bruce"));
        return List.copyOf(hints);
    }

    private static SlashCommandHint hint(String value, String description) {
        return new SlashCommandHint(value, description, true);
    }

    record SlashCommandHint(String value, String description, boolean complete) {
    }
}
