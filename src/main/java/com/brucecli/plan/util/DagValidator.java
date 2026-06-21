package com.brucecli.plan.util;

import com.brucecli.plan.model.ExecutionPlan;
import com.brucecli.plan.model.Task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DAG 校验与拓扑排序。
 *
 * <p>文章评论里也提到了：DFS 反向结果就是拓扑排序。
 * 这里用三色标记法检查环：
 * 0 = 未访问，1 = 访问中，2 = 已完成。
 * 如果 DFS 时再次遇到访问中的节点，说明存在环。</p>
 */
public final class DagValidator {
    private DagValidator() {
    }

    public static void validate(ExecutionPlan plan) {
        topologicalOrder(plan);
    }

    public static List<Task> topologicalOrder(ExecutionPlan plan) {
        Map<String, Task> taskMap = plan.taskMap();
        Map<String, Integer> state = new HashMap<>();
        List<Task> ordered = new ArrayList<>();

        for (Task task : plan.tasks()) {
            dfs(task, taskMap, state, ordered);
        }
        return ordered;
    }

    private static void dfs(
        Task task,
        Map<String, Task> taskMap,
        Map<String, Integer> state,
        List<Task> ordered
    ) {
        int currentState = state.getOrDefault(task.id(), 0);
        if (currentState == 1) {
            throw new IllegalStateException("DAG 中存在环，任务参与环路: " + task.id());
        }
        if (currentState == 2) {
            return;
        }

        state.put(task.id(), 1);
        for (String dependencyId : task.dependencies()) {
            Task dependency = taskMap.get(dependencyId);
            if (dependency == null) {
                throw new IllegalStateException("任务 " + task.id() + " 依赖不存在的任务: " + dependencyId);
            }
            dfs(dependency, taskMap, state, ordered);
        }
        state.put(task.id(), 2);
        ordered.add(task);
    }
}
