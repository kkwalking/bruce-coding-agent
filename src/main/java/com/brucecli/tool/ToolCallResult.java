package com.brucecli.tool;

import com.brucecli.llm.ToolCall;
import com.brucecli.llm.ContentPart;

import java.util.List;
import java.util.Objects;

/**
 * 一次工具调用的执行结果。
 */
public record ToolCallResult(
    ToolCall toolCall,
    String result,
    Status status,
    long durationMillis,
    List<ContentPart> imageParts
) {
    public ToolCallResult(ToolCall toolCall, String result, Status status, long durationMillis) {
        this(toolCall, result, status, durationMillis, List.of());
    }

    public ToolCallResult {
        Objects.requireNonNull(toolCall, "toolCall");
        Objects.requireNonNull(status, "status");
        result = result == null ? "" : result;
        imageParts = imageParts == null ? List.of() : List.copyOf(imageParts);
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis 不能小于 0");
        }
    }

    public static ToolCallResult success(ToolCall toolCall, String result, long durationMillis) {
        return new ToolCallResult(toolCall, result, Status.SUCCESS, durationMillis);
    }

    public static ToolCallResult success(
        ToolCall toolCall,
        String result,
        long durationMillis,
        List<ContentPart> imageParts
    ) {
        return new ToolCallResult(toolCall, result, Status.SUCCESS, durationMillis, imageParts);
    }

    public static ToolCallResult failed(ToolCall toolCall, Throwable cause, long durationMillis) {
        String message = cause == null || cause.getMessage() == null
            ? "未知错误"
            : cause.getMessage();
        return new ToolCallResult(
            toolCall,
            "工具执行失败: " + message,
            Status.FAILED,
            durationMillis
        );
    }

    public static ToolCallResult timeout(ToolCall toolCall, long timeoutMillis) {
        return new ToolCallResult(
            toolCall,
            "工具执行超时，已取消: " + toolCall.function().name(),
            Status.TIMEOUT,
            timeoutMillis
        );
    }

    public static ToolCallResult interrupted(ToolCall toolCall) {
        return new ToolCallResult(
            toolCall,
            "工具批次执行被中断",
            Status.INTERRUPTED,
            0
        );
    }

    public boolean hasImageParts() {
        return !imageParts.isEmpty();
    }

    public enum Status {
        SUCCESS,
        FAILED,
        TIMEOUT,
        INTERRUPTED
    }
}
