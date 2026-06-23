package com.brucecli.skill;

import java.nio.file.Path;

public record SkillDefinition(
    String name,
    String description,
    String instructions,
    Path rootDirectory,
    Path skillFile,
    SkillSource source
) {
    public SkillDefinition {
        name = name == null ? "" : name.trim();
        description = description == null ? "" : description.trim();
        instructions = instructions == null ? "" : instructions.strip();
        rootDirectory = rootDirectory.toAbsolutePath().normalize();
        skillFile = skillFile.toAbsolutePath().normalize();
    }
}
