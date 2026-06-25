package com.brucecli.render;

import com.brucecli.integrated.runtime.IntegratedRuntime;
import com.brucecli.integrated.runtime.RuntimeStatus;
import com.brucecli.llm.ChatClient;

import java.nio.file.Path;

public record BruceStatusInfo(
    String model,
    String provider,
    String mode,
    String phase,
    Path workspace,
    boolean hitlEnabled,
    boolean parallelEnabled,
    boolean ragEnabled,
    boolean ragIndexed,
    boolean webEnabled,
    String mcpSummary,
    int skillCount,
    int memoryTokens,
    long elapsedMillis
) {
    public static BruceStatusInfo from(IntegratedRuntime runtime, ChatClient chatClient, String phase) {
        RuntimeStatus status = runtime.status();
        return new BruceStatusInfo(
            safe(chatClient.getModelName(), "auto"),
            safe(chatClient.getProviderName(), "unknown"),
            status.mode().name().toLowerCase(),
            safe(phase, "idle"),
            status.workspaceRoot(),
            status.hitlEnabled(),
            status.parallelEnabled(),
            status.ragEnabled(),
            status.ragIndexed(),
            status.webEnabled(),
            status.mcpSummary(),
            status.skillCount(),
            status.memoryStatus().shortTermTokens(),
            0
        );
    }

    public BruceStatusInfo withPhase(String nextPhase) {
        return new BruceStatusInfo(
            model,
            provider,
            mode,
            safe(nextPhase, "idle"),
            workspace,
            hitlEnabled,
            parallelEnabled,
            ragEnabled,
            ragIndexed,
            webEnabled,
            mcpSummary,
            skillCount,
            memoryTokens,
            elapsedMillis
        );
    }

    public BruceStatusInfo withElapsedMillis(long nextElapsedMillis) {
        return new BruceStatusInfo(
            model,
            provider,
            mode,
            phase,
            workspace,
            hitlEnabled,
            parallelEnabled,
            ragEnabled,
            ragIndexed,
            webEnabled,
            mcpSummary,
            skillCount,
            memoryTokens,
            Math.max(0, nextElapsedMillis)
        );
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
