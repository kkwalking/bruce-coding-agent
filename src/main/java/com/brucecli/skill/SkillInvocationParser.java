package com.brucecli.skill;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SkillInvocationParser {
    private static final int MAX_EXPLICIT_SKILLS = 3;
    private static final Pattern LEADING_SKILL = Pattern.compile("^\\$([a-z0-9._-]+)(?:\\s+|$)");

    public SkillInvocation parse(String input) {
        String remaining = input == null ? "" : input.strip();
        List<String> names = new ArrayList<>();
        Set<String> uniqueNames = new LinkedHashSet<>();

        while (true) {
            Matcher matcher = LEADING_SKILL.matcher(remaining);
            if (!matcher.find()) {
                break;
            }
            String name = matcher.group(1);
            if (uniqueNames.add(name)) {
                names.add(name);
                if (names.size() > MAX_EXPLICIT_SKILLS) {
                    throw new IllegalArgumentException("一次最多显式指定 3 个 Skill");
                }
            }
            remaining = remaining.substring(matcher.end()).stripLeading();
        }

        if (!names.isEmpty() && remaining.isBlank()) {
            throw new IllegalArgumentException("显式 Skill 后缺少任务内容，用法: $skill-name <任务>");
        }
        return new SkillInvocation(names, remaining);
    }
}
