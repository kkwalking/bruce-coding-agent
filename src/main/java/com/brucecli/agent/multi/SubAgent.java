package com.brucecli.agent.multi;

import com.brucecli.llm.ChatClient;
import com.brucecli.llm.ChatResponse;
import com.brucecli.llm.Message;
import com.brucecli.llm.ToolCall;
import com.brucecli.agent.multi.model.AgentMessage;
import com.brucecli.agent.multi.model.AgentRole;
import com.brucecli.agent.multi.model.ExecutionStep;
import com.brucecli.tool.ToolRegistry;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 子 Agent 实现。
 *
 * <p>每个 SubAgent 有自己的 role、system prompt 和 chatHistory；
 * 它们共享同一个 ChatClient 与 ToolRegistry，从而既能角色隔离，又能共享工具能力。</p>
 */
public class SubAgent {
    private static final int DEFAULT_MAX_ITERATIONS = 120;
    private static final int TOOL_OUTPUT_PREVIEW = 500;

    private final String name;
    private final AgentRole role;
    private final ChatClient llmClient;
    private final ToolRegistry toolRegistry;
    private final int maxIterations;
    private final String additionalSystemPrompt;
    private final List<Message> chatHistory = new ArrayList<>();

    public SubAgent(
        String name,
        AgentRole role,
        ChatClient llmClient,
        ToolRegistry toolRegistry,
        String additionalSystemPrompt
    ) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        this.name = name;
        this.role = role;
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.maxIterations = DEFAULT_MAX_ITERATIONS;
        this.additionalSystemPrompt = additionalSystemPrompt == null ? "" : additionalSystemPrompt.strip();
        clearHistory();
    }

    public String name() {
        return name;
    }

    public AgentMessage plan(String userTask, String memoryPrompt) {
        ensureRole(AgentRole.PLANNER);
        String input = """
            用户任务:
            %s

            可用上下文:
            %s

            请输出一个 JSON 对象，不要输出 Markdown。格式:
            {
              "goal": "用户目标",
              "steps": [
                {
                  "id": "step_1",
                  "description": "可执行步骤描述",
                  "type": "ANALYSIS|FILE_READ|FILE_WRITE|COMMAND|VERIFICATION|GENERAL",
                  "dependencies": []
                }
              ]
            }
            """.formatted(userTask, blankToNone(memoryPrompt));
        return run(input, false, AgentMessage.Type.RESULT, System.out);
    }

    public AgentMessage execute(
        ExecutionStep step,
        String originalTask,
        String completedContext,
        String feedback,
        PrintStream out
    ) {
        ensureRole(AgentRole.WORKER);
        String input = """
            原始用户任务:
            %s

            当前步骤:
            - id: %s
            - type: %s
            - description: %s

            已完成步骤上下文:
            %s

            Reviewer 反馈:
            %s

            请完成当前步骤。需要读取文件、写文件、列目录或执行命令时，必须调用工具。
            完成后用中文总结你做了什么，并给出关键结果。
            """.formatted(
                originalTask,
                step.id(),
                step.type(),
                step.description(),
                blankToNone(completedContext),
                blankToNone(feedback)
            );
        return run(input, true, AgentMessage.Type.RESULT, out);
    }

    public AgentMessage review(String stepDescription, String executionResult, PrintStream out) {
        ensureRole(AgentRole.REVIEWER);
        String input = """
            请审查 Worker 的执行结果是否满足步骤要求。

            步骤要求:
            %s

            执行结果:
            %s

            只输出 JSON 对象，不要输出 Markdown。格式:
            {
              "approved": true,
              "summary": "一句话审查结论",
              "issues": [],
              "suggestions": []
            }

            如果证据不足、结果含糊、没有完成步骤要求，approved 必须为 false。
            """.formatted(stepDescription, executionResult);
        return run(input, false, AgentMessage.Type.RESULT, out);
    }

    public synchronized void clearHistory() {
        chatHistory.clear();
        chatHistory.add(Message.system(appendSystemPrompt(systemPrompt(role))));
    }

    private synchronized AgentMessage run(
        String input,
        boolean allowTools,
        AgentMessage.Type resultType,
        PrintStream out
    ) {
        chatHistory.add(Message.user(input));
        List<com.brucecli.llm.ToolDefinition> tools = allowTools ? toolRegistry.getToolDefinitions() : List.of();

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            ChatResponse response;
            try {
                response = llmClient.chat(chatHistory, tools);
            } catch (IOException e) {
                return AgentMessage.error(name, role, "LLM 调用失败: " + e.getMessage());
            } catch (RuntimeException e) {
                return AgentMessage.error(name, role, "执行失败: " + e.getMessage());
            }

            if (allowTools && response.hasToolCalls()) {
                chatHistory.add(Message.assistant(response.content(), response.toolCalls()));
                for (ToolCall toolCall : response.toolCalls()) {
                    String toolName = toolCall.function().name();
                    String arguments = toolCall.function().arguments();
                    out.printf("[%s] 调用工具 %s %s%n", name, toolName, arguments);
                    String toolResult = toolRegistry.executeTool(toolName, arguments);
                    out.printf("[%s] 工具结果: %s%n", name, abbreviate(toolResult, TOOL_OUTPUT_PREVIEW));
                    chatHistory.add(Message.tool(toolCall.id(), toolResult));
                }
                continue;
            }

            chatHistory.add(Message.assistant(response.content()));
            return new AgentMessage(name, role, response.content(), resultType);
        }

        return AgentMessage.error(name, role, "达到最大迭代次数限制: " + maxIterations);
    }

    private void ensureRole(AgentRole expected) {
        if (role != expected) {
            throw new IllegalStateException(name + " 不是 " + expected + "，当前角色: " + role);
        }
    }

    private static String systemPrompt(AgentRole role) {
        return switch (role) {
            case PLANNER -> """
                你是 Multi-Agent 团队里的规划者。
                你的职责是把用户目标拆成清晰、可执行、依赖关系明确的步骤。
                只输出 JSON，不要输出 Markdown 或额外解释。
                步骤 id 使用 step_1、step_2 这样的稳定格式。
                依赖关系必须只引用已经存在的步骤 id，不能产生循环依赖。
                """;
            case WORKER -> """
                你是 Multi-Agent 团队里的执行者。
                你的职责是完成编排器分配的单个步骤，可以调用工具读文件、写文件、列目录、执行命令。
                你只处理当前步骤，不要擅自改动无关文件。
                如果 Reviewer 给出反馈，请基于反馈重新执行并明确说明修复点。
                """;
            case REVIEWER -> """
                你是 Multi-Agent 团队里的检查者。
                你的职责是审查 Worker 的执行结果是否满足步骤要求。
                你必须保守审批：证据不足、结果模糊、未覆盖要求时都要拒绝。
                只输出 JSON，其中 approved 是布尔值，issues 和 suggestions 是字符串数组。
                """;
        };
    }

    private static String blankToNone(String text) {
        return text == null || text.isBlank() ? "无" : text;
    }

    private String appendSystemPrompt(String basePrompt) {
        if (additionalSystemPrompt.isBlank()) {
            return basePrompt;
        }
        return basePrompt + "\n" + additionalSystemPrompt;
    }

    private static String abbreviate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars) + "...";
    }
}
