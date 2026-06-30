package com.brucecli.tool;

import com.brucecli.tool.ToolCallExecutor;
import com.brucecli.tool.ToolCallResult;
import com.brucecli.tool.ToolCallRunner;
import com.brucecli.runtime.ConcurrencyConfig;
import com.brucecli.runtime.DaemonThreadFactory;
import com.brucecli.llm.ToolCall;
import com.brucecli.tool.ToolRegistry;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

/**
 * ReAct 路径里的并行工具执行器。
 */
public final class ParallelToolCallExecutor implements ToolCallExecutor, AutoCloseable {
    private final ConcurrencyConfig config;
    private final ToolCallRunner toolCallRunner;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ParallelToolCallExecutor(ToolRegistry toolRegistry, ConcurrencyConfig config) {
        Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.config = Objects.requireNonNull(config, "config");
        this.toolCallRunner = new ToolCallRunner(toolRegistry, config::truncate);
        this.executor = Executors.newFixedThreadPool(
            config.maxParallelism(),
            new DaemonThreadFactory("bruce-coding-agent-tool")
        );
    }

    @Override
    public List<ToolCallResult> execute(List<ToolCall> toolCalls) {
        ensureOpen();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return List.of();
        }

        // 单工具不开线程池，少一点调度开销，也更方便对照原始 ReAct 流程。
        if (toolCalls.size() == 1) {
            return List.of(toolCallRunner.run(toolCalls.get(0)));
        }

        List<Callable<ToolCallResult>> tasks = toolCalls.stream()
            .<Callable<ToolCallResult>>map(toolCall -> () -> toolCallRunner.run(toolCall))
            .toList();

        try {
            List<Future<ToolCallResult>> futures = executor.invokeAll(
                tasks,
                config.batchTimeout().toMillis(),
                TimeUnit.MILLISECONDS
            );
            return IntStream.range(0, futures.size())
                .mapToObj(index -> resolve(toolCalls.get(index), futures.get(index)))
                .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return toolCalls.stream()
                .map(ToolCallResult::interrupted)
                .toList();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
        }
    }

    private ToolCallResult resolve(ToolCall toolCall, Future<ToolCallResult> future) {
        if (future.isCancelled()) {
            return ToolCallResult.timeout(toolCall, config.batchTimeout().toMillis());
        }
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ToolCallResult.interrupted(toolCall);
        } catch (ExecutionException exception) {
            return ToolCallResult.failed(toolCall, exception.getCause(), 0);
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("并行工具执行器已经关闭");
        }
    }
}
