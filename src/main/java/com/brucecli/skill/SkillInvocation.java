package com.brucecli.skill;

import java.util.List;

public record SkillInvocation(List<String> skillNames, String task) {
    public SkillInvocation {
        skillNames = skillNames == null ? List.of() : List.copyOf(skillNames);
        task = task == null ? "" : task.strip();
    }
}
