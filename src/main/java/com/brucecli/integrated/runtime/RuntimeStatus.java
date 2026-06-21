package com.brucecli.integrated.runtime;

import java.time.Duration;
import java.nio.file.Path;
import java.util.List;

public record RuntimeStatus(
    AgentMode mode,
    Path workspaceRoot,
    boolean memoryEnabled,
    boolean ragEnabled,
    boolean webEnabled,
    String webSearchProvider,
    boolean hitlEnabled,
    boolean parallelEnabled,
    int maxParallelism,
    Duration batchTimeout,
    boolean ragIndexed,
    List<String> toolNames
) {
    public String toDisplayString() {
        return """
            当前模式: %s
            工作目录: %s
            Memory: %s
            RAG: %s
            RAG 索引: %s
            Web: %s
            HITL: %s
            Parallel: %s
            Tools: %s
            """.formatted(
                mode,
                workspaceRoot,
                memoryEnabled ? "开启" : "关闭",
                ragEnabled ? "开启" : "关闭",
                ragIndexed ? "已建立" : "未建立",
                webEnabled ? "开启 (provider=" + webSearchProvider + ")" : "关闭",
                hitlEnabled ? "开启" : "关闭",
                parallelEnabled
                    ? "开启 (max=" + maxParallelism + ", timeout=" + batchTimeout.toSeconds() + "s)"
                    : "关闭",
                String.join(", ", toolNames)
            );
    }
}
