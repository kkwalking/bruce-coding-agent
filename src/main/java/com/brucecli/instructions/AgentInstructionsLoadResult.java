package com.brucecli.instructions;

import java.util.List;

public record AgentInstructionsLoadResult(String prompt, List<String> diagnostics) {
    public AgentInstructionsLoadResult {
        prompt = prompt == null ? "" : prompt;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
