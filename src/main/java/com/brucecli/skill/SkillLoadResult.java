package com.brucecli.skill;

import java.util.List;

public record SkillLoadResult(
    List<SkillDefinition> skills,
    List<String> diagnostics,
    List<String> overrides
) {
    public SkillLoadResult {
        skills = skills == null ? List.of() : List.copyOf(skills);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        overrides = overrides == null ? List.of() : List.copyOf(overrides);
    }
}
