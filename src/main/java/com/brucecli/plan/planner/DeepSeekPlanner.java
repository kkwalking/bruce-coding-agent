package com.brucecli.plan.planner;

import com.brucecli.llm.ChatClient;
import com.brucecli.llm.ChatResponse;
import com.brucecli.llm.Message;
import com.brucecli.llm.ToolCall;
import com.brucecli.plan.model.ExecutionPlan;
import com.brucecli.plan.model.TaskStatus;
import com.brucecli.tool.ToolRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 DeepSeek 的规划器。
 *
 * <p>与 ReAct 不同，这里不让模型一步一步调用工具。
 * 它只负责一次性产出结构化计划，后续执行阶段由 Java 的 DAG 执行器完成。</p>
 */
public class DeepSeekPlanner implements Planner {
    private static final int MAX_RESOURCE_ITERATIONS = 5;
    private static final String BASE_SYSTEM_PROMPT = """
        你是 BruceCLI 的 Plan-and-Execute 规划器。
        你的任务是把用户目标拆解为可执行 DAG 任务，只返回 JSON，不要返回 Markdown。

        JSON 格式必须是：
        {
          "goal": "用户目标",
          "summary": "一句话说明计划思路",
          "tasks": [
            {
              "id": "t1",
              "description": "任务说明",
              "type": "PLANNING | FILE_READ | FILE_WRITE | COMMAND | ANALYSIS | VERIFICATION",
              "dependencies": [],
              "path": "文件路径，可选",
              "content": "写入文件内容，可选",
              "command": "Shell 命令，可选"
            }
          ]
        }

        规则：
        1. id 使用 t1、t2、t3 这种稳定短 ID。
        2. dependencies 只能引用已经存在的任务 ID。
        3. 计划必须是 DAG，不能有环。
        4. 文件读取用 FILE_READ，文件写入用 FILE_WRITE，命令执行用 COMMAND，验证用 VERIFICATION。
        5. 如果用户要求创建、编译、运行项目，应给出完整步骤和依赖关系。
        """;

    private final ChatClient chatClient;
    private final PlanJsonParser parser;
    private final String systemPrompt;
    private final ToolRegistry planningToolRegistry;

    public DeepSeekPlanner(ChatClient chatClient, String additionalSystemPrompt) {
        this(chatClient, additionalSystemPrompt, null);
    }

    public DeepSeekPlanner(
        ChatClient chatClient,
        String additionalSystemPrompt,
        ToolRegistry planningToolRegistry
    ) {
        this(chatClient, new PlanJsonParser(), additionalSystemPrompt, planningToolRegistry);
    }

    private DeepSeekPlanner(
        ChatClient chatClient,
        PlanJsonParser parser,
        String additionalSystemPrompt,
        ToolRegistry planningToolRegistry
    ) {
        this.chatClient = chatClient;
        this.parser = parser;
        this.systemPrompt = appendSystemPrompt(additionalSystemPrompt);
        this.planningToolRegistry = planningToolRegistry;
    }

    @Override
    public ExecutionPlan plan(String userGoal) throws IOException {
        return plan(userGoal, "");
    }

    @Override
    public ExecutionPlan plan(String userGoal, String supplementalContext) throws IOException {
        return plan(userGoal, supplementalContext, "");
    }

    @Override
    public ExecutionPlan plan(
        String userGoal,
        String supplementalContext,
        String taskSystemContext
    ) throws IOException {
        String userMessage = userGoal;
        if (supplementalContext != null && !supplementalContext.isBlank()) {
            userMessage = """
                用户原始目标:
                %s

                可用补充上下文:
                %s

                请只围绕用户原始目标制定计划。补充上下文仅用于提高计划准确性。
                """.formatted(userGoal, supplementalContext);
        }
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(systemPrompt));
        if (taskSystemContext != null && !taskSystemContext.isBlank()) {
            messages.add(Message.system(taskSystemContext));
        }
        messages.add(Message.user(userMessage));

        for (int iteration = 0; iteration < MAX_RESOURCE_ITERATIONS; iteration++) {
            List<com.brucecli.llm.ToolDefinition> tools = planningToolRegistry == null
                ? List.of()
                : planningToolRegistry.getToolDefinitions();
            ChatResponse response = chatClient.chat(messages, tools);
            if (!response.hasToolCalls()) {
                return parser.parse(response.content(), userGoal);
            }
            messages.add(Message.assistant(response.content(), response.toolCalls()));
            for (ToolCall toolCall : response.toolCalls()) {
                String result = planningToolRegistry == null
                    ? "规划阶段不允许调用工具"
                    : planningToolRegistry.executeTool(
                        toolCall.function().name(),
                        toolCall.function().arguments()
                    );
                messages.add(Message.tool(toolCall.id(), result));
            }
        }
        throw new IOException("规划器读取 Skill 资源次数超过限制");
    }

    @Override
    public ExecutionPlan replan(ExecutionPlan failedPlan, String failureReason) throws IOException {
        return replan(failedPlan, failureReason, "");
    }

    @Override
    public ExecutionPlan replan(
        ExecutionPlan failedPlan,
        String failureReason,
        String supplementalContext
    ) throws IOException {
        return replan(failedPlan, failureReason, supplementalContext, "");
    }

    @Override
    public ExecutionPlan replan(
        ExecutionPlan failedPlan,
        String failureReason,
        String supplementalContext,
        String taskSystemContext
    ) throws IOException {
        String context = buildContext(failedPlan, failureReason);
        return plan(context, supplementalContext, taskSystemContext);
    }

    private String buildContext(ExecutionPlan failedPlan, String failureReason) {
        StringBuilder builder = new StringBuilder();
        builder.append("请基于以下失败上下文重新规划任务。\n");
        builder.append("原目标: ").append(failedPlan.goal()).append('\n');
        builder.append("失败原因: ").append(failureReason).append('\n');
        builder.append("已完成任务:\n");
        failedPlan.tasks().stream()
            .filter(task -> task.status() == TaskStatus.COMPLETED)
            .forEach(task -> builder
                .append("- ").append(task.id())
                .append(": ").append(task.description())
                .append(" => ").append(task.result())
                .append('\n'));
        builder.append("请保留已经完成的有效结果，只规划剩余需要执行的步骤。");
        return builder.toString();
    }

    private String appendSystemPrompt(String additionalSystemPrompt) {
        if (additionalSystemPrompt == null || additionalSystemPrompt.isBlank()) {
            return BASE_SYSTEM_PROMPT;
        }
        return BASE_SYSTEM_PROMPT + "\n" + additionalSystemPrompt.strip();
    }
}
