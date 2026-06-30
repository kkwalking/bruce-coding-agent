package com.brucecli.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillLoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsUserAndProjectSkillsWithProjectOverrideAndStableOrder() throws Exception {
        Path home = tempDir.resolve("home");
        Path project = tempDir.resolve("project");
        writeSkill(home.resolve(".bruce/skills/z-user"), "z-user", "用户 Skill", "用户指令");
        writeSkill(home.resolve(".bruce/skills/shared"), "shared", "用户版本", "用户版本指令");
        writeSkill(project.resolve(".bruce/skills/a-project"), "a-project", "项目 Skill", "项目指令");
        writeSkill(project.resolve(".bruce/skills/shared"), "shared", "项目版本", "项目版本指令");

        SkillLoadResult result = new SkillLoader(home, project).load();

        assertEquals(
            java.util.List.of("a-project", "shared", "z-user"),
            result.skills().stream().map(SkillDefinition::name).toList()
        );
        SkillDefinition shared = result.skills().stream()
            .filter(skill -> skill.name().equals("shared"))
            .findFirst()
            .orElseThrow();
        assertEquals(SkillSource.PROJECT, shared.source());
        assertEquals("项目版本指令", shared.instructions());
        assertEquals(1, result.overrides().size());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void reportsInvalidSkillsWithoutBlockingValidOnes() throws Exception {
        Path home = tempDir.resolve("home-invalid");
        Path project = tempDir.resolve("project-invalid");
        writeSkill(project.resolve(".bruce/skills/good"), "good", "有效", "有效指令");
        Path invalid = project.resolve(".bruce/skills/bad/SKILL.md");
        Files.createDirectories(invalid.getParent());
        Files.writeString(invalid, """
            ---
            name: Bad Name
            description:
            ---
            """);

        SkillLoadResult result = new SkillLoader(home, project).load();

        assertEquals(1, result.skills().size());
        assertEquals("good", result.skills().get(0).name());
        assertFalse(result.diagnostics().isEmpty());
        assertTrue(result.diagnostics().get(0).contains("name 必须匹配"));
    }

    @Test
    void missingDirectoriesProduceEmptyResultWithoutDiagnostics() {
        SkillLoadResult result = new SkillLoader(
            tempDir.resolve("missing-home"),
            tempDir.resolve("missing-project")
        ).load();

        assertTrue(result.skills().isEmpty());
        assertTrue(result.diagnostics().isEmpty());
    }

    private void writeSkill(
        Path directory,
        String name,
        String description,
        String instructions
    ) throws Exception {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), """
            ---
            name: %s
            description: %s
            ---

            %s
            """.formatted(name, description, instructions));
    }
}
