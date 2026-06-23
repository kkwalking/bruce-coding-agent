package com.brucecli.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillManagerTest {
    @TempDir
    Path tempDir;

    @Test
    void catalogContainsOnlyMetadataAndIsBounded() throws Exception {
        Path home = tempDir.resolve("catalog-home");
        Path project = tempDir.resolve("catalog-project");
        for (int i = 0; i < 100; i++) {
            writeSkill(
                project,
                "skill-" + i,
                "d".repeat(100),
                "SECRET_INSTRUCTION_" + i
            );
        }
        SkillManager manager = new SkillManager(home, project);

        String catalog = manager.catalogPrompt();

        assertTrue(catalog.length() <= 8_000);
        assertTrue(catalog.contains("skill-0"));
        assertFalse(catalog.contains("SECRET_INSTRUCTION"));
    }

    @Test
    void loadSkillActivatesInstructionsAndIsIdempotent() throws Exception {
        Path home = tempDir.resolve("load-home");
        Path project = tempDir.resolve("load-project");
        writeSkill(project, "review", "代码审查", "执行审查流程");
        SkillManager manager = new SkillManager(home, project);
        manager.beginTask();

        String first = manager.loadSkill("review");
        String second = manager.loadSkill("review");

        assertEquals(first, second);
        assertTrue(first.contains("执行审查流程"));
        assertEquals(first, manager.activeInstructions());
        assertTrue(manager.isActive("review"));
        manager.endTask();
        assertFalse(manager.isActive("review"));
        assertTrue(manager.activeInstructions().isBlank());
    }

    @Test
    void limitsActiveSkillsAndInstructionBudget() throws Exception {
        Path home = tempDir.resolve("limit-home");
        Path project = tempDir.resolve("limit-project");
        writeSkill(project, "one", "一", "x".repeat(30_000));
        writeSkill(project, "two", "二", "two");
        writeSkill(project, "three", "三", "three");
        writeSkill(project, "four", "四", "four");
        SkillManager manager = new SkillManager(home, project);
        manager.beginTask();

        String first = manager.loadSkill("one");

        assertEquals(24_000, first.length());
        assertThrows(IllegalStateException.class, () -> manager.loadSkill("two"));
        manager.endTask();

        manager.beginTask();
        manager.loadSkill("two");
        manager.loadSkill("three");
        manager.loadSkill("four");
        assertThrows(IllegalStateException.class, () -> manager.loadSkill("one"));
    }

    @Test
    void readsOnlyLoadedSkillResourcesAndTruncatesLargeFiles() throws Exception {
        Path home = tempDir.resolve("resource-home");
        Path project = tempDir.resolve("resource-project");
        Path skillDirectory = writeSkill(project, "docs", "读取资料", "先读取资料");
        Files.createDirectories(skillDirectory.resolve("references"));
        Files.writeString(skillDirectory.resolve("references/guide.txt"), "hello");
        Files.writeString(skillDirectory.resolve("references/large.txt"), "x".repeat(13_000));
        SkillManager manager = new SkillManager(home, project);
        manager.beginTask();

        assertThrows(
            IllegalArgumentException.class,
            () -> manager.readResource("docs", "references/guide.txt")
        );
        manager.loadSkill("docs");
        assertEquals("hello", manager.readResource("docs", "references/guide.txt"));
        assertTrue(manager.readResource("docs", "references/large.txt").contains("已截断"));
        assertThrows(IllegalArgumentException.class, () -> manager.readResource("docs", "../outside.txt"));
        assertThrows(IllegalArgumentException.class, () -> manager.readResource("docs", tempDir.toString()));
        assertThrows(IllegalArgumentException.class, () -> manager.readResource("docs", "references"));
        manager.endTask();
    }

    @Test
    void rejectsUnknownSkillsAndSymlinkEscape() throws Exception {
        Path home = tempDir.resolve("link-home");
        Path project = tempDir.resolve("link-project");
        Path skillDirectory = writeSkill(project, "safe", "安全读取", "读取资料");
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "secret");
        Path link = skillDirectory.resolve("escape.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException e) {
            return;
        }
        SkillManager manager = new SkillManager(home, project);
        manager.beginTask();

        assertThrows(IllegalArgumentException.class, () -> manager.loadSkill("missing"));
        manager.loadSkill("safe");
        IllegalArgumentException error = assertThrows(
            IllegalArgumentException.class,
            () -> manager.readResource("safe", "escape.txt")
        );
        assertTrue(error.getMessage().contains("符号链接逃逸"));
    }

    private Path writeSkill(Path project, String name, String description, String instructions) throws Exception {
        Path directory = project.resolve(".brucecli/skills").resolve(name);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), """
            ---
            name: %s
            description: %s
            ---

            %s
            """.formatted(name, description, instructions));
        return directory;
    }
}
