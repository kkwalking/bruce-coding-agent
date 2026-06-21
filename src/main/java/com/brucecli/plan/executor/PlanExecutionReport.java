package com.brucecli.plan.executor;

import com.brucecli.plan.model.ExecutionPlan;
import com.brucecli.plan.model.Task;
import com.brucecli.plan.model.TaskStatus;

/**
 * 计划执行报告。
 *
 * <p>执行器返回这个对象，CLI 可以把它格式化给用户看；
 * 测试也可以用它检查每个任务最终状态。</p>
 */
public record PlanExecutionReport(ExecutionPlan plan, long startTime, long endTime) {
    public boolean success() {
        return plan.tasks().stream().allMatch(task -> task.status() == TaskStatus.COMPLETED);
    }

    /**
     * 任务成功率，用于文章“规划的自我修正”里的判断：
     * 如果成功率很低，说明初始计划可能不适合当前环境，可以让 LLM 重新规划。
     */
    public double successRate() {
        if (plan.tasks().isEmpty()) {
            return 0;
        }
        long completed = plan.tasks().stream()
            .filter(task -> task.status() == TaskStatus.COMPLETED)
            .count();
        return completed * 1.0 / plan.tasks().size();
    }

    public long durationMillis() {
        return endTime - startTime;
    }

    public String toMarkdown() {
        StringBuilder builder = new StringBuilder();
        builder.append("# 执行报告\n\n");
        builder.append("- 目标: ").append(plan.goal()).append('\n');
        builder.append("- 总耗时: ").append(durationMillis()).append(" ms\n");
        builder.append("- 是否成功: ").append(success() ? "是" : "否").append("\n\n");
        builder.append("## 计划视图\n\n");
        builder.append("```text\n");
        builder.append(plan.visualize());
        builder.append("```\n\n");
        builder.append("## 任务结果\n\n");
        builder.append("| 任务 | 类型 | 状态 | 耗时 | 结果摘要 |\n");
        builder.append("| --- | --- | --- | ---: | --- |\n");
        for (Task task : plan.tasks()) {
            String summary = task.status() == TaskStatus.COMPLETED ? task.result() : task.error();
            builder.append("| ")
                .append(task.id()).append(" ")
                .append(escape(task.description())).append(" | ")
                .append(task.type()).append(" | ")
                .append(task.status()).append(" | ")
                .append(task.durationMillis()).append(" ms | ")
                .append(escape(firstLine(summary))).append(" |\n");
        }
        return builder.toString();
    }

    private String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String line = text.lines().findFirst().orElse("");
        return line.length() > 80 ? line.substring(0, 80) + "..." : line;
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("|", "\\|").replace("\n", " ");
    }
}
