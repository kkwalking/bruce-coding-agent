package com.brucecli.integrated.runtime;

import com.brucecli.memory.core.MemoryStatus;

import java.time.Duration;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public record RuntimeStatus(
    AgentMode mode,
    String model,
    String provider,
    Path workspaceRoot,
    MemoryStatus memoryStatus,
    boolean ragEnabled,
    boolean webEnabled,
    String webSearchProvider,
    String mcpSummary,
    boolean hitlEnabled,
    boolean parallelEnabled,
    int maxParallelism,
    Duration batchTimeout,
    boolean ragIndexed,
    int skillCount,
    List<String> toolNames
) {
    private static final String MCP_TOOL_PREFIX = "mcp__";
    private static final Set<String> HIDDEN_DISPLAY_TOOLS = Set.of(
        "load_skill",
        "read_skill_resource",
        "save_long_term_memory"
    );

    public String toDisplayString() {
        return """
            当前模式: %s
            当前模型: %s [%s]
            工作目录: %s
            Memory: (短期 %d 条 / %d tokens，长期 %d 条，最近注入 %d tokens)
            RAG: %s
            RAG 索引: %s
            Web: %s
            MCP: %s
            HITL: %s
            Parallel: %s
            Skills: %s
            Tools: %s
            """.formatted(
                mode,
                model,
                provider,
                workspaceRoot,
                memoryStatus.shortTermEntries(),
                memoryStatus.shortTermTokens(),
                memoryStatus.longTermEntries(),
                memoryStatus.lastContextTokens(),
                ragEnabled ? "开启" : "关闭",
                ragIndexed ? "已建立" : "未建立",
                webEnabled ? "开启 (provider=" + webSearchProvider + ")" : "关闭",
                mcpSummary,
                hitlEnabled ? "开启" : "关闭",
                parallelEnabled
                    ? "开启 (max=" + maxParallelism + ", timeout=" + batchTimeout.toSeconds() + "s)"
                    : "关闭",
                skillCount + " 个",
                String.join(", ", displayToolNames())
            );
    }

    private List<String> displayToolNames() {
        return toolNames.stream()
            .filter(RuntimeStatus::isDisplayTool)
            .toList();
    }

    private static boolean isDisplayTool(String toolName) {
        return toolName != null
            && !toolName.startsWith(MCP_TOOL_PREFIX)
            && !HIDDEN_DISPLAY_TOOLS.contains(toolName);
    }
}
