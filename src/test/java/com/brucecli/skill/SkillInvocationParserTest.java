package com.brucecli.skill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillInvocationParserTest {
    private final SkillInvocationParser parser = new SkillInvocationParser();

    @Test
    void parsesOneOrMultipleLeadingSkillsAndRemovesThemFromTask() {
        SkillInvocation one = parser.parse("$code-reviewer 审查当前变更");
        SkillInvocation multiple = parser.parse(
            "$code-reviewer $security-reviewer 审查登录模块"
        );

        assertEquals(List.of("code-reviewer"), one.skillNames());
        assertEquals("审查当前变更", one.task());
        assertEquals(
            List.of("code-reviewer", "security-reviewer"),
            multiple.skillNames()
        );
        assertEquals("审查登录模块", multiple.task());
    }

    @Test
    void deduplicatesNamesAndOnlyParsesAtInputStart() {
        SkillInvocation duplicate = parser.parse("$review $review 检查代码");
        SkillInvocation inline = parser.parse("解释 $review 的含义");

        assertEquals(List.of("review"), duplicate.skillNames());
        assertTrue(inline.skillNames().isEmpty());
        assertEquals("解释 $review 的含义", inline.task());
    }

    @Test
    void rejectsMoreThanThreeSkillsAndMissingTask() {
        assertThrows(
            IllegalArgumentException.class,
            () -> parser.parse("$one $two $three $four 执行任务")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> parser.parse("$code-reviewer")
        );
    }
}
