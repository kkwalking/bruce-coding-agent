package com.brucecli.plan.util;

import com.brucecli.plan.model.ExecutionPlan;
import com.brucecli.plan.model.Task;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 计划校验器，对应文章“更智能的规划”里的 validatePlan 思路。
 *
 * <p>DAG 校验会直接抛异常，这里返回错误列表，更适合给 LLM 重新规划时作为反馈。</p>
 */
public class PlanValidator {
    public List<String> validatePlan(ExecutionPlan plan) {
        List<String> errors = new ArrayList<>();

        Set<String> ids = new HashSet<>();
        for (Task task : plan.tasks()) {
            if (!ids.add(task.id())) {
                errors.add("重复任务 ID: " + task.id());
            }
            for (String dependencyId : task.dependencies()) {
                if (plan.task(dependencyId) == null) {
                    errors.add("任务 " + task.id() + " 依赖不存在的任务: " + dependencyId);
                }
            }
        }

        try {
            plan.validateDag();
        } catch (RuntimeException e) {
            errors.add(e.getMessage());
        }

        return errors;
    }
}
