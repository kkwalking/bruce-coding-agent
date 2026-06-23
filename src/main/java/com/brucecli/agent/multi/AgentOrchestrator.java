package com.brucecli.agent.multi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.brucecli.llm.ChatClient;
import com.brucecli.memory.core.MemoryContext;
import com.brucecli.memory.core.MemoryManager;
import com.brucecli.agent.multi.SubAgent;
import com.brucecli.agent.multi.model.AgentMessage;
import com.brucecli.agent.multi.model.AgentRole;
import com.brucecli.agent.multi.model.ExecutionPlan;
import com.brucecli.agent.multi.model.ExecutionStep;
import com.brucecli.agent.multi.model.MultiAgentResult;
import com.brucecli.agent.multi.model.StepStatus;
import com.brucecli.agent.multi.planner.ExecutionPlanParser;
import com.brucecli.tool.ToolRegistry;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Multi-Agent 编排器。
 *
 * <p>它是主从架构里的“主”：找 Planner 拆任务，解析成 ExecutionStep，
 * 找出当前可执行步骤，分配 Worker 并发执行，再交给 Reviewer 审查。
 * 审查未通过时带着反馈让 Worker 最多重试 2 次。</p>
 */
public class AgentOrchestrator implements AutoCloseable {
    private final ChatClient llmClient;
    private final ToolRegistry toolRegistry;
    private final MemoryManager memoryManager;
    private final SubAgent planner;
    private final List<SubAgent> workers;
    private final BlockingQueue<SubAgent> workerPool;
    private final SubAgent reviewer;
    private final ExecutorService executorService;
    private final ExecutionPlanParser planParser = new ExecutionPlanParser();
    private final ObjectMapper mapper = new ObjectMapper();
    private final int maxReviewRetries;
    private final Duration batchTimeout;
    private final Supplier<String> skillContextSupplier;

    public AgentOrchestrator(
        ChatClient llmClient,
        ToolRegistry toolRegistry,
        MemoryManager memoryManager,
        int workerCount,
        int maxReviewRetries,
        String additionalSystemPrompt,
        Duration batchTimeout,
        ThreadFactory threadFactory
    ) {
        this(
            llmClient,
            toolRegistry,
            null,
            memoryManager,
            workerCount,
            maxReviewRetries,
            additionalSystemPrompt,
            batchTimeout,
            threadFactory,
            () -> ""
        );
    }

    public AgentOrchestrator(
        ChatClient llmClient,
        ToolRegistry toolRegistry,
        ToolRegistry planningToolRegistry,
        MemoryManager memoryManager,
        int workerCount,
        int maxReviewRetries,
        String additionalSystemPrompt,
        Duration batchTimeout,
        ThreadFactory threadFactory
    ) {
        this(
            llmClient,
            toolRegistry,
            planningToolRegistry,
            memoryManager,
            workerCount,
            maxReviewRetries,
            additionalSystemPrompt,
            batchTimeout,
            threadFactory,
            () -> ""
        );
    }

    public AgentOrchestrator(
        ChatClient llmClient,
        ToolRegistry toolRegistry,
        ToolRegistry planningToolRegistry,
        MemoryManager memoryManager,
        int workerCount,
        int maxReviewRetries,
        String additionalSystemPrompt,
        Duration batchTimeout,
        ThreadFactory threadFactory,
        Supplier<String> skillContextSupplier
    ) {
        if (workerCount <= 0) {
            throw new IllegalArgumentException("workerCount 必须大于 0");
        }
        if (batchTimeout == null || batchTimeout.isZero() || batchTimeout.isNegative()) {
            throw new IllegalArgumentException("batchTimeout 必须为正数");
        }
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.memoryManager = memoryManager;
        this.maxReviewRetries = maxReviewRetries;
        this.batchTimeout = batchTimeout;
        this.skillContextSupplier = skillContextSupplier == null ? () -> "" : skillContextSupplier;
        this.planner = new SubAgent(
            "planner",
            AgentRole.PLANNER,
            llmClient,
            planningToolRegistry,
            additionalSystemPrompt
        );

        List<SubAgent> createdWorkers = new ArrayList<>();
        for (int i = 1; i <= workerCount; i++) {
            createdWorkers.add(new SubAgent(
                "worker-" + i,
                AgentRole.WORKER,
                llmClient,
                toolRegistry,
                additionalSystemPrompt
            ));
        }
        this.workers = List.copyOf(createdWorkers);
        this.workerPool = new LinkedBlockingQueue<>(createdWorkers);
        this.reviewer = new SubAgent("reviewer", AgentRole.REVIEWER, llmClient, toolRegistry, additionalSystemPrompt);
        this.executorService = Executors.newFixedThreadPool(
            workerCount,
            threadFactory == null ? Executors.defaultThreadFactory() : threadFactory
        );
    }

    public MultiAgentResult execute(String userTask, String supplementalContext, PrintStream out) {
        return execute(userTask, supplementalContext, "", out);
    }

    public MultiAgentResult execute(
        String userTask,
        String supplementalContext,
        String taskSkillContext,
        PrintStream out
    ) {
        if (userTask == null || userTask.isBlank()) {
            return new MultiAgentResult("", false, List.of(), "请输入任务内容", 0);
        }

        long start = System.currentTimeMillis();
        rememberUserMessage(userTask);

        String memoryPrompt = joinContext(buildMemoryPrompt(userTask), supplementalContext);
        out.println("[orchestrator] 请求 Planner 拆解任务...");
        AgentMessage planMessage = planner.plan(userTask, memoryPrompt, taskSkillContext);
        ExecutionPlan plan = parseOrFallback(userTask, planMessage, out);

        out.printf("[orchestrator] 计划生成完成，共 %d 个步骤%n", plan.steps().size());
        while (plan.hasPendingSteps()) {
            List<ExecutionStep> skipped = plan.skipBlockedSteps();
            for (ExecutionStep step : skipped) {
                out.printf("[orchestrator] 跳过步骤 %s: %s%n", step.id(), step.error());
            }

            List<ExecutionStep> executableSteps = plan.getExecutableSteps();
            if (executableSteps.isEmpty()) {
                break;
            }

            executeStepBatch(userTask, plan, executableSteps, taskSkillContext, out);
        }

        boolean success = plan.allStepsCompleted();
        String summary = buildSummary(plan, success);
        rememberAssistantMessage(summary);
        return new MultiAgentResult(
            userTask,
            success,
            plan.steps(),
            summary,
            System.currentTimeMillis() - start
        );
    }

    private String joinContext(String first, String second) {
        if (first == null || first.isBlank()) {
            return second == null ? "" : second;
        }
        if (second == null || second.isBlank()) {
            return first;
        }
        return first + "\n\n" + second;
    }

    public void clearHistories() {
        planner.clearHistory();
        workers.forEach(SubAgent::clearHistory);
        reviewer.clearHistory();
    }

    public boolean parseReviewApproval(String reviewContent) {
        try {
            String json = extractJsonObject(reviewContent);
            JsonNode root = mapper.readTree(json);
            JsonNode approved = root.path("approved");
            return approved.isBoolean() && approved.asBoolean();
        } catch (Exception ignored) {
            return reviewContent != null
                && reviewContent.matches("(?is).*\\\"approved\\\"\\s*:\\s*true.*");
        }
    }

    public String parseReviewFeedback(String reviewContent) {
        try {
            String json = extractJsonObject(reviewContent);
            JsonNode root = mapper.readTree(json);
            StringBuilder builder = new StringBuilder();
            appendText(builder, root.path("summary").asText(""));
            appendArray(builder, "issues", root.path("issues"));
            appendArray(builder, "suggestions", root.path("suggestions"));
            String feedback = builder.toString().trim();
            return feedback.isBlank() ? "Reviewer 未给出具体反馈" : feedback;
        } catch (Exception ignored) {
            return reviewContent == null || reviewContent.isBlank() ? "Reviewer 未给出具体反馈" : reviewContent;
        }
    }

    @Override
    public void close() {
        executorService.shutdownNow();
    }

    private Callable<Void> runStepTask(
        String userTask,
        ExecutionPlan plan,
        ExecutionStep step,
        String taskSkillContext,
        PrintStream out
    ) {
        return () -> {
            runStep(userTask, plan, step, taskSkillContext, out);
            return null;
        };
    }

    private void runStep(
        String userTask,
        ExecutionPlan plan,
        ExecutionStep step,
        String taskSkillContext,
        PrintStream out
    ) {
        String feedback = step.error();
        while (true) {
            step.markStarted();
            SubAgent worker;
            try {
                worker = workerPool.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                step.markFailed("获取 Worker 被中断");
                return;
            }

            try {
                out.printf("[%s] 开始执行 %s: %s%n", worker.name(), step.id(), step.description());
                AgentMessage result = worker.execute(
                    step,
                    userTask,
                    buildStepContext(plan),
                    feedback,
                    joinContext(taskSkillContext, skillContextSupplier.get()),
                    out
                );
                if (result.type() == AgentMessage.Type.ERROR) {
                    step.markFailed(result.content());
                    return;
                }

                AgentMessage review = reviewer.review(step.description(), result.content(), out);
                boolean approved = parseReviewApproval(review.content());
                if (approved) {
                    out.printf("[reviewer] %s 审查通过%n", step.id());
                    step.markCompleted(result.content());
                    return;
                }

                feedback = parseReviewFeedback(review.content());
                if (step.attempts() > maxReviewRetries) {
                    out.printf("[reviewer] %s 审查未通过，达到重试上限%n", step.id());
                    step.markFailed("审查未通过: " + feedback);
                    return;
                }

                out.printf("[reviewer] %s 审查未通过，准备第 %d 次重试: %s%n",
                    step.id(), step.attempts(), feedback);
                step.markRetryable(feedback);
            } finally {
                workerPool.offer(worker);
            }
        }
    }

    private void executeStepBatch(
        String userTask,
        ExecutionPlan plan,
        List<ExecutionStep> executableSteps,
        String taskSkillContext,
        PrintStream out
    ) {
        List<Callable<Void>> callables = executableSteps.stream()
            .map(step -> runStepTask(userTask, plan, step, taskSkillContext, out))
            .toList();
        try {
            List<Future<Void>> futures = executorService.invokeAll(
                callables,
                batchTimeout.toMillis(),
                TimeUnit.MILLISECONDS
            );
            waitForSteps(futures, executableSteps, out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.println("[orchestrator] 批次执行被中断");
            markUnfinishedAsFailed(executableSteps, "Worker 批次执行被中断");
        }
    }

    private void waitForSteps(List<Future<Void>> futures, List<ExecutionStep> steps, PrintStream out) {
        for (int i = 0; i < futures.size(); i++) {
            Future<Void> future = futures.get(i);
            ExecutionStep step = steps.get(i);
            if (future.isCancelled()) {
                out.printf("[orchestrator] 步骤 %s 执行超时，已取消%n", step.id());
                markUnfinishedAsFailed(List.of(step), "Worker 步骤执行超时，已取消: " + step.id());
                continue;
            }
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                out.println("[orchestrator] 等待步骤执行被中断");
                markUnfinishedAsFailed(steps, "等待步骤执行被中断");
                return;
            } catch (Exception e) {
                out.println("[orchestrator] 步骤执行异常: " + e.getMessage());
                markUnfinishedAsFailed(List.of(step), "步骤执行异常: " + e.getMessage());
            }
        }
    }

    private void markUnfinishedAsFailed(List<ExecutionStep> steps, String reason) {
        for (ExecutionStep step : steps) {
            if (step.status() == StepStatus.PENDING || step.status() == StepStatus.RUNNING) {
                step.markFailed(reason);
            }
        }
    }

    private ExecutionPlan parseOrFallback(String userTask, AgentMessage planMessage, PrintStream out) {
        if (planMessage.type() == AgentMessage.Type.ERROR) {
            out.println("[orchestrator] Planner 失败，回退为单步骤计划: " + planMessage.content());
            return planParser.fallbackPlan(userTask);
        }
        try {
            return planParser.parse(planMessage.content(), userTask);
        } catch (Exception e) {
            out.println("[orchestrator] 计划解析失败，回退为单步骤计划: " + e.getMessage());
            return planParser.fallbackPlan(userTask);
        }
    }

    private String buildMemoryPrompt(String userTask) {
        if (memoryManager == null) {
            return "";
        }
        try {
            MemoryContext context = memoryManager.buildContext(userTask);
            return context.prompt();
        } catch (IOException e) {
            return "Memory 构建失败: " + e.getMessage();
        }
    }

    private String buildStepContext(ExecutionPlan plan) {
        String completed = plan.completedContext();
        return completed.isBlank() ? "暂无已完成步骤。" : completed;
    }

    private String buildSummary(ExecutionPlan plan, boolean success) {
        StringBuilder builder = new StringBuilder();
        builder.append(success ? "所有步骤已通过 Reviewer 审查。" : "部分步骤失败或被跳过。").append('\n');
        for (ExecutionStep step : plan.steps()) {
            builder.append("- ")
                .append(step.id())
                .append(" [").append(step.status()).append("] ")
                .append(step.description());
            if (step.status() == StepStatus.COMPLETED) {
                builder.append(" -> ").append(abbreviate(step.result(), 500));
            } else if (step.error() != null && !step.error().isBlank()) {
                builder.append(" -> ").append(abbreviate(step.error(), 500));
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    private void rememberUserMessage(String userTask) {
        if (memoryManager != null) {
            memoryManager.rememberUserMessage(userTask);
        }
    }

    private void rememberAssistantMessage(String summary) {
        if (memoryManager != null) {
            memoryManager.rememberAssistantMessage(summary);
        }
    }

    private String extractJsonObject(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("没有 JSON 内容");
        }
        String text = rawText.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("无法提取 JSON 对象");
        }
        return text.substring(start, end + 1);
    }

    private void appendArray(StringBuilder builder, String label, JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            return;
        }
        builder.append(label).append(": ");
        List<String> items = new ArrayList<>();
        for (JsonNode item : array) {
            items.add(item.asText());
        }
        builder.append(String.join("; ", items)).append('\n');
    }

    private void appendText(StringBuilder builder, String text) {
        if (text != null && !text.isBlank()) {
            builder.append(text).append('\n');
        }
    }

    private static String abbreviate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars) + "...";
    }
}
