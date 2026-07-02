package com.brucecli.agent;

import com.brucecli.llm.ChatClient;
import com.brucecli.llm.ChatResponse;
import com.brucecli.llm.ContentPart;
import com.brucecli.llm.Message;
import com.brucecli.llm.MessageHistoryPruner;
import com.brucecli.llm.PreparedUserInput;
import com.brucecli.llm.ToolCall;
import com.brucecli.event.BruceEventSink;
import com.brucecli.event.BruceEvents;
import com.brucecli.tool.ToolCallExecutor;
import com.brucecli.tool.ToolCallResult;
import com.brucecli.tool.ToolRegistry;
import com.brucecli.skill.SkillToolRegistrar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Agent 的核心：维护对话历史，并驱动 ReAct 循环。
 *
 * <p>ReAct 的关键不是“一次模型调用得到答案”，而是：
 * 模型先决定要不要调用工具；程序执行工具并把结果写回历史；
 * 模型再根据工具结果继续推理，直到给出最终回答。</p>
 */
public class Agent {
    private static final Logger logger = LoggerFactory.getLogger(Agent.class);

    // 防止模型反复调用工具导致无限循环。真实生产系统通常还会加 token、时间、费用等限制。
    private static final int DEFAULT_MAX_ITERATIONS = 40;

    // 网络抖动时允许短暂重试，避免一次 IOException 直接中断任务。
    private static final int MAX_RETRIES = 2;

    // System Prompt 相当于 Agent 的“操作手册”，它告诉模型有哪些工具、什么时候该用工具。
    private static final String SYSTEM_PROMPT = """
        你是一个智能编程助手，可以帮助用户完成各种任务。

        你可以使用以下工具来完成任务：
        1. read_file - 读取文件内容
        2. write_file - 写入文件内容
        3. list_dir - 列出目录内容
        4. execute_command - 执行 Shell 命令
        5. create_project - 创建新项目结构

        当需要操作文件、执行命令或创建项目时，请使用工具调用。
        使用工具后，根据工具返回的结果继续思考下一步行动。
        如果任务已经完成，请直接给出最终答复，不要继续调用工具。

        请用中文回复用户。
        """;

    private final ChatClient llmClient;
    private final ToolRegistry toolRegistry;
    private final ToolCallExecutor toolCallExecutor;
    private final String systemPrompt;
    private final BruceEventSink eventSink;

    // conversationHistory 是 ReAct 循环的上下文载体：用户问题、模型工具调用、工具结果都会放进去。
    private final List<Message> conversationHistory = new ArrayList<>();
    private final int maxIterations;

    public Agent(
        ChatClient llmClient,
        ToolRegistry toolRegistry,
        String additionalSystemPrompt,
        ToolCallExecutor toolCallExecutor
    ) {
        this(
            llmClient,
            toolRegistry,
            additionalSystemPrompt,
            toolCallExecutor,
            BruceEventSink.NO_OP
        );
    }

    public Agent(
        ChatClient llmClient,
        ToolRegistry toolRegistry,
        String additionalSystemPrompt,
        ToolCallExecutor toolCallExecutor,
        BruceEventSink eventSink
    ) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.maxIterations = DEFAULT_MAX_ITERATIONS;
        this.toolCallExecutor = toolCallExecutor == null
            ? ToolCallExecutor.serial(toolRegistry)
            : toolCallExecutor;
        this.systemPrompt = appendSystemPrompt(additionalSystemPrompt);
        this.eventSink = eventSink == null ? BruceEventSink.NO_OP : eventSink;

        // 每个 Agent 实例启动时都先放入 system prompt，保证模型知道自己的角色和工具规则。
        clearHistory();
    }

    /**
     * 执行一次用户任务。
     *
     * <p>一次任务可能触发多轮模型调用。只要模型返回 tool_calls，就执行工具并继续下一轮；
     * 如果模型没有返回 tool_calls，就认为任务完成并返回最终文本。</p>
     */
    public String run(String userInput) {
        return run(userInput, "");
    }

    public String run(String userInput, String taskSystemContext) {
        return run(PreparedUserInput.text(userInput), taskSystemContext);
    }

    public String run(String userInput, String taskSystemContext, String runId) {
        return run(PreparedUserInput.text(userInput), taskSystemContext, runId);
    }

    public String run(PreparedUserInput userInput, String taskSystemContext) {
        return run(userInput, taskSystemContext, BruceEvents.newRunId());
    }

    public String run(PreparedUserInput userInput, String taskSystemContext, String runId) {
        PreparedUserInput preparedInput = userInput == null ? PreparedUserInput.text("") : userInput;
        if (preparedInput.text().isBlank()) {
            return "请输入任务内容";
        }

        logger.info("[Agent] 开始任务: {}", preparedInput.text());

        Message temporaryContext = null;
        if (taskSystemContext != null && !taskSystemContext.isBlank()) {
            temporaryContext = Message.system(taskSystemContext);
            conversationHistory.add(temporaryContext);
        }

        // 用户输入必须加入历史，否则模型不知道这一轮要做什么。
        appendDurableMessage(runId, preparedInput.message());

        try {
            int iteration = 0;
            int retryCount = 0;
            while (iteration < maxIterations) {
                iteration++;

                ChatResponse response;
                try {
                    MessageHistoryPruner.retainLatestImageMessage(conversationHistory);
                    // 每次都发送完整历史和完整工具清单，让模型可以基于之前的观察继续行动。
                    emit(new BruceEvents.MessageStarted(runId, Message.ROLE_ASSISTANT));
                    response = llmClient.chat(
                        conversationHistory,
                        toolRegistry.getToolDefinitions(),
                        streamListener(runId)
                    );
                    retryCount = 0;
                } catch (IOException e) {
                    if (retryCount < MAX_RETRIES) {
                        retryCount++;
                        logger.warn("[Agent] LLM 调用失败，准备重试: {}", e.getMessage());
                        continue;
                    }
                    return nonDurableAssistant(runId, "网络错误: " + e.getMessage());
                } catch (Exception e) {
                    return nonDurableAssistant(runId, "执行错误: " + e.getMessage());
                }

                if (response.hasToolCalls()) {
                    // 这条 assistant 消息非常重要：它记录“模型刚才请求了哪些工具”。
                    appendDurableMessage(runId, Message.assistant(response));
                    for (ToolCall toolCall : response.toolCalls()) {
                        emit(new BruceEvents.ToolCallStarted(runId, toolCall));
                    }
                    List<ToolCallResult> toolResults = toolCallExecutor.execute(response.toolCalls());
                    for (ToolCallResult toolResult : toolResults) {
                        ToolCall toolCall = toolResult.toolCall();
                        emit(new BruceEvents.ToolCallCompleted(runId, toolResult));
                        logger.info(
                            "[Agent] 工具 {} 完成。",
                            toolCall.function().name()
                        );

                        // 工具返回值要以 tool message 的形式写回历史，下一轮模型会读取它作为 Observation。
                        Message toolMessage = Message.tool(toolCall.id(), toolResult.result());
                        conversationHistory.add(toolMessage);
                        emit(new BruceEvents.MessageCompleted(
                            runId,
                            durableToolMessage(toolCall, toolMessage),
                            true
                        ));
                    }
                    appendImageToolMessages(runId, toolResults);

                    // 工具执行完不直接返回给用户，而是继续让模型基于工具结果生成下一步或最终回答。
                    continue;
                }

                // 没有工具调用，说明模型已经给出最终回答。
                appendDurableMessage(runId, Message.assistant(response));
                return response.content();
            }

            String stopped = "达到最大迭代次数限制";
            appendDurableMessage(runId, Message.assistant(stopped));
            return stopped;
        } finally {
            if (temporaryContext != null) {
                conversationHistory.remove(temporaryContext);
            }
            redactSkillToolResults();
        }
    }

    public void clearHistory() {
        conversationHistory.clear();

        // 清空历史后仍保留 system prompt；否则模型会忘记工具使用规则。
        conversationHistory.add(Message.system(systemPrompt));
    }

    public void restoreHistory(List<Message> messages) {
        clearHistory();
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (Message message : messages) {
            if (message == null || Message.ROLE_SYSTEM.equals(message.role())) {
                continue;
            }
            conversationHistory.add(message);
        }
    }

    private String appendSystemPrompt(String additionalSystemPrompt) {
        if (additionalSystemPrompt == null || additionalSystemPrompt.isBlank()) {
            return SYSTEM_PROMPT;
        }
        return SYSTEM_PROMPT + "\n" + additionalSystemPrompt.strip();
    }

    private ChatClient.StreamListener streamListener(String runId) {
        return new ChatClient.StreamListener() {
            @Override
            public void onReasoningDelta(String delta) {
                emit(new BruceEvents.MessageDelta(runId, Message.ROLE_ASSISTANT, "reasoning", delta));
            }

            @Override
            public void onContentDelta(String delta) {
                emit(new BruceEvents.MessageDelta(runId, Message.ROLE_ASSISTANT, "content", delta));
            }
        };
    }

    private void appendImageToolMessages(String runId, List<ToolCallResult> toolResults) {
        for (ToolCallResult result : toolResults) {
            if (!result.hasImageParts()) {
                continue;
            }
            List<ContentPart> parts = new ArrayList<>();
            parts.add(ContentPart.text(
                "工具 " + result.toolCall().function().name() + " 返回了图片内容，请结合上面的工具文本结果分析。"
            ));
            parts.addAll(result.imageParts());
            appendDurableMessage(runId, Message.user(parts));
        }
    }

    private void appendDurableMessage(String runId, Message message) {
        conversationHistory.add(message);
        emit(new BruceEvents.MessageCompleted(runId, message, true));
    }

    private String nonDurableAssistant(String runId, String content) {
        emit(new BruceEvents.MessageCompleted(runId, Message.assistant(content), false));
        return content;
    }

    private Message durableToolMessage(ToolCall toolCall, Message toolMessage) {
        if (SkillToolRegistrar.isSkillTool(toolCall.function().name())) {
            return Message.tool(toolMessage.toolCallId(), "[Skill 内容仅对原任务有效，已从历史中移除]");
        }
        return toolMessage;
    }

    private void redactSkillToolResults() {
        Set<String> skillCallIds = new HashSet<>();
        for (Message message : conversationHistory) {
            if (message.toolCalls() == null) {
                continue;
            }
            message.toolCalls().stream()
                .filter(call -> SkillToolRegistrar.isSkillTool(call.function().name()))
                .map(ToolCall::id)
                .forEach(skillCallIds::add);
        }
        for (int i = 0; i < conversationHistory.size(); i++) {
            Message message = conversationHistory.get(i);
            if (Message.ROLE_TOOL.equals(message.role()) && skillCallIds.contains(message.toolCallId())) {
                conversationHistory.set(
                    i,
                    Message.tool(message.toolCallId(), "[Skill 内容仅对原任务有效，已从历史中移除]")
                );
            }
        }
    }

    private void emit(com.brucecli.event.BruceEvent event) {
        eventSink.emit(event);
    }
}
