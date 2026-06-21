package com.brucecli.tool;

import com.brucecli.llm.ToolCall;
import com.brucecli.tool.ToolRegistry;

import java.util.List;
import java.util.Objects;

/**
 * 把一批 tool_calls 转成一批 tool message 所需的执行结果。
 */
@FunctionalInterface
public interface ToolCallExecutor {
    List<ToolCallResult> execute(List<ToolCall> toolCalls);

    static ToolCallExecutor serial(ToolRegistry toolRegistry) {
        Objects.requireNonNull(toolRegistry, "toolRegistry");
        ToolCallRunner runner = new ToolCallRunner(toolRegistry);
        return toolCalls -> {
            if (toolCalls == null || toolCalls.isEmpty()) {
                return List.of();
            }
            return toolCalls.stream()
                .map(runner::run)
                .toList();
        };
    }
}
