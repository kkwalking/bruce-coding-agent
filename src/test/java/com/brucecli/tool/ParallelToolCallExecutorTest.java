package com.brucecli.tool;

import com.brucecli.tool.ToolCallResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.brucecli.runtime.ConcurrencyConfig;
import com.brucecli.llm.FunctionCall;
import com.brucecli.llm.ToolCall;
import com.brucecli.tool.Tool;
import com.brucecli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelToolCallExecutorTest {
    @TempDir
    Path tempDir;

    @Test
    void executesIndependentToolCallsInParallelAndKeepsOrder() {
        ToolRegistry registry = new ToolRegistry(tempDir);
        registerSleepTool(registry);
        ConcurrencyConfig config = new ConcurrencyConfig(3, Duration.ofSeconds(2), 2_000);
        List<ToolCall> calls = List.of(
            call("call_1", "slow", 320),
            call("call_2", "fast", 80),
            call("call_3", "middle", 160)
        );

        List<ToolCallResult> results;
        long elapsed;
        try (ParallelToolCallExecutor executor = new ParallelToolCallExecutor(registry, config)) {
            long start = System.currentTimeMillis();
            results = executor.execute(calls);
            elapsed = System.currentTimeMillis() - start;
        }

        assertTrue(elapsed < 520, "并行耗时应接近最慢任务，而不是三者相加: " + elapsed);
        assertEquals(List.of("call_1", "call_2", "call_3"), results.stream()
            .map(result -> result.toolCall().id())
            .toList());
        assertTrue(results.get(0).result().contains("slow"));
        assertTrue(results.get(1).result().contains("fast"));
        assertTrue(results.get(2).result().contains("middle"));
        assertTrue(results.stream().allMatch(result -> result.status() == ToolCallResult.Status.SUCCESS));
    }

    @Test
    void singleToolCallRunsDirectly() {
        ToolRegistry registry = new ToolRegistry(tempDir);
        registerSleepTool(registry);
        List<ToolCallResult> results;
        try (ParallelToolCallExecutor executor = new ParallelToolCallExecutor(
            registry,
            new ConcurrencyConfig(4, Duration.ofSeconds(2), 2_000)
        )) {
            results = executor.execute(List.of(call("only", "single", 10)));
        }

        assertEquals(1, results.size());
        assertEquals("only", results.get(0).toolCall().id());
        assertTrue(results.get(0).result().contains("single"));
        assertEquals(ToolCallResult.Status.SUCCESS, results.get(0).status());
    }

    @Test
    void marksUnfinishedCallsAsTimedOutAndKeepsCompletedResults() {
        ToolRegistry registry = new ToolRegistry(tempDir);
        registerSleepTool(registry);
        ConcurrencyConfig config = new ConcurrencyConfig(2, Duration.ofMillis(120), 2_000);

        List<ToolCallResult> results;
        try (ParallelToolCallExecutor executor = new ParallelToolCallExecutor(registry, config)) {
            results = executor.execute(List.of(
                call("fast", "fast", 20),
                call("slow", "slow", 500)
            ));
        }

        assertEquals(ToolCallResult.Status.SUCCESS, results.get(0).status());
        assertEquals(ToolCallResult.Status.TIMEOUT, results.get(1).status());
        assertTrue(results.get(1).result().contains("超时"));
    }

    @Test
    void rejectsNewBatchesAfterClose() {
        ToolRegistry registry = new ToolRegistry(tempDir);
        ParallelToolCallExecutor executor = new ParallelToolCallExecutor(
            registry,
            new ConcurrencyConfig(2, Duration.ofSeconds(1), 2_000)
        );

        executor.close();

        assertThrows(IllegalStateException.class, () -> executor.execute(List.of(
            call("late", "late", 10)
        )));
    }

    private ToolCall call(String id, String name, int delayMillis) {
        return new ToolCall(
            id,
            new FunctionCall(
                "sleep_tool",
                "{\"name\":\"%s\",\"delay_ms\":\"%d\"}".formatted(name, delayMillis)
            )
        );
    }

    private void registerSleepTool(ToolRegistry registry) {
        ObjectNode schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("name").put("type", "string");
        properties.putObject("delay_ms").put("type", "string");
        schema.putArray("required").add("name").add("delay_ms");

        registry.register(new Tool(
            "sleep_tool",
            "测试用睡眠工具",
            schema,
            args -> {
                Thread.sleep(Long.parseLong(args.get("delay_ms")));
                return args.get("name") + " done";
            }
        ));
    }
}
