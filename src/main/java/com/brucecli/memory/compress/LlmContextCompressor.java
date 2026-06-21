package com.brucecli.memory.compress;

import com.brucecli.llm.ChatClient;
import com.brucecli.llm.ChatResponse;
import com.brucecli.llm.Message;
import com.brucecli.memory.model.MemoryEntry;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 基于大模型的上下文压缩器。
 *
 * <p>CLI 运行时会使用 DeepSeekClient 作为 ChatClient。
 * 压缩阶段不传 tools，只要求模型保留用户偏好、项目约定、已完成动作和重要工具结果。</p>
 */
public class LlmContextCompressor implements ContextCompressor {
    private static final String SYSTEM_PROMPT = """
        你是 BruceCLI 的上下文压缩器。
        请把历史对话压缩成简短但有用的记忆摘要，保留：
        1. 用户明确偏好；
        2. 项目技术栈、目录、文件名、命令；
        3. Agent 已经完成的动作；
        4. 工具结果里的关键事实。
        不要保留寒暄和重复内容。请用中文输出。
        """;

    private final ChatClient chatClient;

    public LlmContextCompressor(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public MemoryEntry compress(List<MemoryEntry> entries) throws IOException {
        if (entries == null || entries.isEmpty()) {
            return MemoryEntry.summary("没有需要压缩的上下文。", Map.of("compressor", "llm"));
        }

        String history = entries.stream()
            .map(MemoryEntry::toPromptLine)
            .collect(Collectors.joining("\n"));
        ChatResponse response = chatClient.chat(List.of(
            Message.system(SYSTEM_PROMPT),
            Message.user(history)
        ), List.of());

        String content = response.content() == null || response.content().isBlank()
            ? "压缩器没有返回内容。"
            : response.content();
        System.out.println("压缩完成，内容如下:\n"+content);
        return MemoryEntry.summary(content, Map.of(
            "compressor", "llm",
            "sourceCount", String.valueOf(entries.size())
        ));
    }
}
