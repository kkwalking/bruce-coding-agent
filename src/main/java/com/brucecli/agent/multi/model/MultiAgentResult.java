package com.brucecli.agent.multi.model;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 一次 Multi-Agent 编排执行的汇总结果。
 */
public record MultiAgentResult(
    String goal,
    boolean success,
    List<ExecutionStep> steps,
    String summary,
    long durationMillis
) {
    public MultiAgentResult {
        steps = steps == null ? List.of() : List.copyOf(steps);
        summary = summary == null ? "" : summary;
    }

    public String toMarkdown() {
        String status = success ? "成功" : "失败";
        String stepLines = steps.stream()
            .map(step -> "- " + step.id()
                + " [" + step.status() + ", attempts=" + step.attempts() + "] "
                + step.description()
                + renderTail(step))
            .collect(Collectors.joining("\n"));
        return """
            ## Multi-Agent 执行结果

            目标: %s
            状态: %s
            耗时: %d ms

            ### 步骤
            %s

            ### 汇总
            %s
            """.formatted(goal, status, durationMillis, stepLines, summary);
    }

    private String renderTail(ExecutionStep step) {
        if (step.status() == StepStatus.COMPLETED) {
            return " -> " + abbreviate(step.result(), 180);
        }
        if (step.error() != null && !step.error().isBlank()) {
            return " -> " + abbreviate(step.error(), 180);
        }
        return "";
    }

    private static String abbreviate(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        if (compact.length() <= maxChars) {
            return compact;
        }
        return compact.substring(0, maxChars) + "...";
    }
}
