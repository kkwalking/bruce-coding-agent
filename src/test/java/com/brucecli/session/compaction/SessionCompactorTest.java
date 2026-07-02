package com.brucecli.session.compaction;

import com.brucecli.config.BruceSettings;
import com.brucecli.integrated.runtime.AgentMode;
import com.brucecli.llm.ChatClient;
import com.brucecli.llm.ChatResponse;
import com.brucecli.llm.FunctionCall;
import com.brucecli.llm.Message;
import com.brucecli.llm.ToolCall;
import com.brucecli.llm.ToolDefinition;
import com.brucecli.session.SessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionCompactorTest {
    @TempDir
    Path tempDir;

    @Test
    void cutPointAvoidsToolResultsAndTracksReadAndWriteFiles() throws Exception {
        SessionManager manager = SessionManager.createNew(tempDir.resolve("home"), tempDir.resolve("workspace"), AgentMode.REACT);
        manager.appendMessage(Message.user("old request"));
        manager.appendMessage(Message.assistant("", "", List.of(
            toolCall("read", "read_file", "{\"path\":\"src/A.java\"}"),
            toolCall("write", "write_file", "{\"path\":\"src/B.java\"}")
        )));
        manager.appendMessage(Message.tool("read", "file content"));
        manager.appendMessage(Message.tool("write", "文件已写入"));
        manager.appendMessage(Message.user("kept request"));
        String kept = manager.activeLeafId();

        CompactionPreparation preparation = SessionCompactor.prepare(manager.activeEntries(), settings(1))
            .orElseThrow();

        assertEquals(kept, preparation.firstKeptEntryId());
        assertFalse(preparation.messagesToSummarize().isEmpty());
        assertEquals(List.of("src/A.java"), preparation.details().readFiles());
        assertEquals(List.of("src/B.java"), preparation.details().modifiedFiles());
    }

    @Test
    void splitTurnKeepsAssistantSuffixAndSummarizesTurnPrefix() throws Exception {
        SessionManager manager = SessionManager.createNew(tempDir.resolve("home"), tempDir.resolve("workspace"), AgentMode.REACT);
        manager.appendMessage(Message.user("请完成一个很长的任务"));
        manager.appendMessage(Message.assistant("前期分析"));
        manager.appendMessage(Message.tool("missing", "观察结果"));
        manager.appendMessage(Message.assistant("保留的后半段"));
        String kept = manager.activeLeafId();

        CompactionPreparation preparation = SessionCompactor.prepare(manager.activeEntries(), settings(1))
            .orElseThrow();

        assertTrue(preparation.splitTurn());
        assertEquals(kept, preparation.firstKeptEntryId());
        assertTrue(preparation.messagesToSummarize().isEmpty());
        assertEquals(List.of("请完成一个很长的任务", "前期分析", "观察结果"), preparation.turnPrefixMessages().stream()
            .map(Message::content)
            .toList());
    }

    @Test
    void compactIncludesCustomInstructionsPreviousSummaryAndFileTags() throws Exception {
        SessionManager manager = SessionManager.createNew(tempDir.resolve("home"), tempDir.resolve("workspace"), AgentMode.REACT);
        manager.appendMessage(Message.user("old"));
        manager.appendMessage(Message.user("kept"));
        String firstKept = manager.activeLeafId();
        manager.appendCompaction("previous summary", firstKept, 100, new CompactionDetails(List.of("README.md"), List.of()));
        manager.appendMessage(Message.assistant("", "", List.of(toolCall("write", "write_file", "{\"path\":\"src/App.java\"}"))));
        manager.appendMessage(Message.user("new kept"));

        CapturingChatClient chatClient = new CapturingChatClient();
        CompactionPreparation preparation = SessionCompactor.prepare(manager.activeEntries(), settings(1))
            .orElseThrow();
        CompactionResult result = SessionCompactor.compact(preparation, chatClient, "只关注实现细节");

        String prompt = chatClient.lastMessages.get(1).content();
        assertTrue(prompt.contains("previous summary"));
        assertTrue(prompt.contains("只关注实现细节"));
        assertTrue(result.summary().contains("summary from model"));
        assertTrue(result.summary().contains("<read-files>"));
        assertTrue(result.summary().contains("README.md"));
        assertTrue(result.summary().contains("<modified-files>"));
        assertTrue(result.summary().contains("src/App.java"));
    }

    @Test
    void compactSplitTurnUsesSeparateTurnPrefixSummary() throws Exception {
        SessionManager manager = SessionManager.createNew(tempDir.resolve("home"), tempDir.resolve("workspace"), AgentMode.REACT);
        manager.appendMessage(Message.user("old request"));
        manager.appendMessage(Message.assistant("old answer"));
        manager.appendMessage(Message.user("当前长任务"));
        manager.appendMessage(Message.assistant("前期分析"));
        manager.appendMessage(Message.tool("missing", "观察结果"));
        manager.appendMessage(Message.assistant("保留的后半段"));

        CompactionPreparation preparation = SessionCompactor.prepare(manager.activeEntries(), settings(1))
            .orElseThrow();
        SplitCapturingChatClient chatClient = new SplitCapturingChatClient();
        CompactionResult result = SessionCompactor.compact(preparation, chatClient, "");

        assertTrue(preparation.splitTurn());
        assertEquals(2, chatClient.prompts().size());
        assertEquals("""
            history summary

            ---

            **Turn Context (split turn):**

            turn prefix summary
            """.strip(), result.summary());
        assertTrue(chatClient.prompts().stream().anyMatch(prompt ->
            prompt.contains("old request")
                && !prompt.contains("当前长任务")
                && !prompt.contains("## Original Request")
        ));
        assertTrue(chatClient.prompts().stream().anyMatch(prompt ->
            prompt.contains("当前长任务")
                && prompt.contains("前期分析")
                && prompt.contains("观察结果")
                && prompt.contains("## Original Request")
        ));
    }

    private static BruceSettings.CompactionSettings settings(int keepRecentTokens) {
        BruceSettings.CompactionSettings settings = new BruceSettings.CompactionSettings();
        settings.setKeepRecentTokens(keepRecentTokens);
        settings.setReserveTokens(16);
        return settings;
    }

    private static ToolCall toolCall(String id, String name, String arguments) {
        return new ToolCall(id, new FunctionCall(name, arguments));
    }

    private static class CapturingChatClient implements ChatClient {
        private List<Message> lastMessages = List.of();

        @Override
        public ChatResponse chat(List<Message> messages, List<ToolDefinition> tools) {
            lastMessages = List.copyOf(messages);
            return new ChatResponse("summary from model", List.of());
        }
    }

    private static class SplitCapturingChatClient implements ChatClient {
        private final List<String> prompts = Collections.synchronizedList(new ArrayList<>());

        @Override
        public ChatResponse chat(List<Message> messages, List<ToolDefinition> tools) {
            String prompt = messages.get(1).content();
            prompts.add(prompt);
            if (prompt.contains("## Original Request")) {
                return new ChatResponse("turn prefix summary", List.of());
            }
            return new ChatResponse("history summary", List.of());
        }

        private List<String> prompts() {
            synchronized (prompts) {
                return List.copyOf(prompts);
            }
        }
    }
}
