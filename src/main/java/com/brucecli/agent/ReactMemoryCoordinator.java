package com.brucecli.agent;

import com.brucecli.llm.Message;
import com.brucecli.llm.ToolCall;
import com.brucecli.memory.core.MemoryContext;
import com.brucecli.memory.core.MemoryManager;
import com.brucecli.skill.SkillToolRegistrar;
import com.brucecli.tool.ToolCallResult;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * ReAct 路径的 Memory 门面。
 *
 * <p>Agent 只负责 ReAct 循环本身；本类集中处理 Memory 上下文构建、
 * 短期记忆写入和 Skill 工具结果脱敏。</p>
 */
public class ReactMemoryCoordinator {
    private final MemoryManager memoryManager;

    public ReactMemoryCoordinator(MemoryManager memoryManager) {
        this.memoryManager = Objects.requireNonNull(memoryManager, "memoryManager");
    }

    public Message beginTurn(String userInput) throws IOException {
        MemoryContext memoryContext = memoryManager.buildContext(userInput);
        memoryManager.rememberUserMessage(userInput);
        return Message.system(memoryContext.prompt());
    }

    public void rememberAssistantAnswer(String answer) {
        memoryManager.rememberAssistantMessage(answer);
    }

    public void rememberToolRequest(List<ToolCall> toolCalls) {
        memoryManager.rememberAssistantMessage("模型请求调用工具: " + renderToolNames(toolCalls));
    }

    public void rememberToolResult(ToolCallResult toolResult) {
        String toolName = toolResult.toolCall().function().name();
        memoryManager.rememberToolResult(
            toolName,
            SkillToolRegistrar.isSkillTool(toolName)
                ? "[Skill 内容仅对当前任务有效，未写入 Memory]"
                : toolResult.result()
        );
    }

    private String renderToolNames(List<ToolCall> toolCalls) {
        return toolCalls.stream()
            .map(call -> call.function().name())
            .toList()
            .toString();
    }
}
