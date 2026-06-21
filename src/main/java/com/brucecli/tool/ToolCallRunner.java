package com.brucecli.tool;

import com.brucecli.llm.ToolCall;
import com.brucecli.tool.ToolRegistry;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * 执行单个工具调用，统一记录耗时并生成结构化结果。
 */
public final class ToolCallRunner {
    private final ToolRegistry toolRegistry;
    private final UnaryOperator<String> resultMapper;

    public ToolCallRunner(ToolRegistry toolRegistry) {
        this(toolRegistry, UnaryOperator.identity());
    }

    public ToolCallRunner(
        ToolRegistry toolRegistry,
        UnaryOperator<String> resultMapper
    ) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.resultMapper = Objects.requireNonNull(resultMapper, "resultMapper");
    }

    public ToolCallResult run(ToolCall toolCall) {
        Objects.requireNonNull(toolCall, "toolCall");
        long start = System.nanoTime();
        try {
            String result = toolRegistry.executeTool(
                toolCall.function().name(),
                toolCall.function().arguments()
            );
            return ToolCallResult.success(
                toolCall,
                resultMapper.apply(result),
                elapsedMillis(start)
            );
        } catch (RuntimeException exception) {
            return ToolCallResult.failed(toolCall, exception, elapsedMillis(start));
        }
    }

    private long elapsedMillis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
