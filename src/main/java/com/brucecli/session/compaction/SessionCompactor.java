package com.brucecli.session.compaction;

import com.brucecli.config.BruceSettings;
import com.brucecli.llm.ChatClient;
import com.brucecli.llm.ChatResponse;
import com.brucecli.llm.ContentPart;
import com.brucecli.llm.Message;
import com.brucecli.llm.ToolCall;
import com.brucecli.llm.ToolDefinition;
import com.brucecli.session.SessionEntry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class SessionCompactor {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TOOL_RESULT_MAX_CHARS = 2_000;
    private static final int ESTIMATED_IMAGE_TOKENS = 1_200;
    private static final String TOOL_READ_FILE = "read_file";
    private static final String TOOL_WRITE_FILE = "write_file";
    private static final String TOOL_EDIT_FILE = "edit_file";
    private static final String TOOL_ARGUMENT_PATH = "path";
    private static final String SYSTEM_PROMPT = """
        You are a context summarization assistant. Read the Bruce Coding Agent conversation excerpt and output only the requested structured summary.
        Do not continue the conversation. Do not answer questions from the transcript.
        """;
    private static final String SUMMARY_FORMAT = """
        请用中文输出结构化摘要，格式如下：

        ## Goal
        [用户要完成的目标]

        ## Constraints & Preferences
        - [用户明确要求、偏好、项目约束]

        ## Progress
        ### Done
        - [x] [已完成事项]

        ### In Progress
        - [ ] [当前正在处理的事项]

        ### Blocked
        - [阻塞或风险，没有就写“无”]

        ## Key Decisions
        - **[决策]**: [原因]

        ## Next Steps
        1. [下一步建议]

        ## Critical Context
        - [继续任务必须保留的文件名、命令、错误、接口、约定]
        """;
    private static final String TURN_PREFIX_SUMMARY_FORMAT = """
        这是一个过长单轮任务的前半部分；后半部分原文会继续保留。

        请总结这段前半部分，为理解后面保留的原文提供上下文。用中文输出，格式如下：

        ## Original Request
        [这一轮里用户最初要求做什么]

        ## Early Progress
        - [前半段已经完成的关键工作、读取/修改过的文件、重要发现]

        ## Context for Suffix
        - [理解后半段保留原文必须知道的信息]
        """;

    private SessionCompactor() {
    }

    public static Optional<CompactionPreparation> prepare(
        List<SessionEntry> pathEntries,
        BruceSettings.CompactionSettings settings
    ) {
        List<SessionEntry> entries = pathEntries == null ? List.of() : List.copyOf(pathEntries);
        if (entries.isEmpty() || SessionEntry.TYPE_COMPACTION.equals(entries.get(entries.size() - 1).type())) {
            return Optional.empty();
        }
        BruceSettings.CompactionSettings effectiveSettings = settings == null
            ? new BruceSettings.CompactionSettings()
            : settings;

        int previousCompactionIndex = latestCompactionIndex(entries);
        String previousSummary = "";
        int boundaryStart = 0;
        FileOperations fileOps = new FileOperations();
        if (previousCompactionIndex >= 0) {
            SessionEntry previous = entries.get(previousCompactionIndex);
            previousSummary = previous.summary() == null ? "" : previous.summary();
            addDetails(fileOps, previous.details());
            int firstKeptIndex = indexOfEntry(entries, previous.firstKeptEntryId());
            // 重复压缩从上一轮 raw tail 的边界开始。更早的内容已经由 previousSummary 表达，
            // 如果再次参与摘要，会造成上下文重复并让摘要逐渐漂移。找不到旧边界时退回到
            // 上一条 compaction 节点之后，保证旧 session 文件仍然可以继续压缩。
            boundaryStart = firstKeptIndex >= 0 ? firstKeptIndex : previousCompactionIndex + 1;
        }

        int boundaryEnd = entries.size();
        int tokensBefore = estimateContextTokens(contextMessages(entries)).tokens();
        // 找到压缩后第一条继续以原文形式保留的 entry。它之前的范围会被摘要替代，
        // 从它到当前 tip 的范围会作为最近上下文原样保留。
        CutPoint cutPoint = findCutPoint(
            entries,
            boundaryStart,
            boundaryEnd,
            effectiveSettings.getKeepRecentTokens()
        );
        if (cutPoint.firstKeptEntryIndex() < 0 || cutPoint.firstKeptEntryIndex() >= entries.size()) {
            return Optional.empty();
        }

        SessionEntry firstKept = entries.get(cutPoint.firstKeptEntryIndex());
        if (firstKept.id() == null || firstKept.id().isBlank()) {
            return Optional.empty();
        }
        // 普通切分：
        //   摘要 [boundaryStart, firstKeptEntryIndex)
        //   原文 [firstKeptEntryIndex, boundaryEnd)
        //
        // turn 内切分：
        //   摘要历史      [boundaryStart, turnStartIndex)
        //   摘要 turn 前缀 [turnStartIndex, firstKeptEntryIndex)
        //   原文保留后缀  [firstKeptEntryIndex, boundaryEnd)
        //
        // 这样既能原样保留最近的后缀，又能把超长单轮任务的原始请求和前半段进展
        // 放入专门的 summary block，避免后半段原文失去上下文。
        int historyEnd = cutPoint.splitTurn()
            ? cutPoint.turnStartIndex()
            : cutPoint.firstKeptEntryIndex();

        List<Message> messagesToSummarize = messagesForRange(entries, boundaryStart, historyEnd);
        List<Message> turnPrefixMessages = cutPoint.splitTurn()
            ? messagesForRange(entries, cutPoint.turnStartIndex(), cutPoint.firstKeptEntryIndex())
            : List.of();

        if (messagesToSummarize.isEmpty() && turnPrefixMessages.isEmpty()) {
            return Optional.empty();
        }

        collectFileOperations(fileOps, messagesToSummarize);
        collectFileOperations(fileOps, turnPrefixMessages);
        CompactionDetails details = details(fileOps);
        return Optional.of(new CompactionPreparation(
            firstKept.id(),
            messagesToSummarize,
            turnPrefixMessages,
            cutPoint.splitTurn(),
            tokensBefore,
            previousSummary,
            details,
            effectiveSettings.getReserveTokens()
        ));
    }

    public static CompactionResult compact(
        CompactionPreparation preparation,
        ChatClient chatClient,
        String customInstructions
    ) throws IOException {
        if (preparation == null) {
            throw new IllegalArgumentException("缺少 compaction preparation。");
        }
        if (chatClient == null) {
            throw new IllegalArgumentException("缺少 ChatClient。");
        }

        String summary = generateSummary(preparation, chatClient, customInstructions);
        summary += formatFileOperations(preparation.details());
        int estimatedTokensAfter = estimateMessagesTokens(List.of(compactionSummaryMessage(summary)));
        return new CompactionResult(
            summary,
            preparation.firstKeptEntryId(),
            preparation.tokensBefore(),
            estimatedTokensAfter,
            preparation.details()
        );
    }

    public static ContextUsageEstimate estimateContextTokens(List<Message> messages) {
        List<Message> source = messages == null ? List.of() : messages;
        int usageIndex = -1;
        int usageTokens = 0;
        for (int i = source.size() - 1; i >= 0; i--) {
            Message message = source.get(i);
            if (message != null && Message.ROLE_ASSISTANT.equals(message.role()) && message.totalUsageTokens() > 0) {
                usageIndex = i;
                usageTokens = message.totalUsageTokens();
                break;
            }
        }
        if (usageIndex < 0) {
            int estimated = estimateMessagesTokens(source);
            return new ContextUsageEstimate(estimated, 0, estimated, -1);
        }
        int trailingTokens = 0;
        for (int i = usageIndex + 1; i < source.size(); i++) {
            trailingTokens += estimateTokens(source.get(i));
        }
        return new ContextUsageEstimate(usageTokens + trailingTokens, usageTokens, trailingTokens, usageIndex);
    }

    public static int estimateMessagesTokens(List<Message> messages) {
        int tokens = 0;
        for (Message message : messages == null ? List.<Message>of() : messages) {
            tokens += estimateTokens(message);
        }
        return tokens;
    }

    public static int estimateTokens(Message message) {
        if (message == null) {
            return 0;
        }
        int tokens = 4;
        tokens += estimateTextTokens(message.role());
        tokens += estimateTextTokens(message.content());
        tokens += estimateTextTokens(message.reasoningContent());
        tokens += estimateTextTokens(message.toolCallId());
        if (message.toolCalls() != null) {
            for (ToolCall call : message.toolCalls()) {
                if (call == null || call.function() == null) {
                    continue;
                }
                tokens += estimateTextTokens(call.function().name());
                tokens += estimateTextTokens(call.function().arguments());
            }
        }
        if (message.contentParts() != null) {
            for (ContentPart part : message.contentParts()) {
                if (part == null) {
                    continue;
                }
                if (part.isImagePart()) {
                    tokens += ESTIMATED_IMAGE_TOKENS;
                } else {
                    tokens += estimateTextTokens(part.fallbackText());
                }
            }
        }
        return Math.max(1, tokens);
    }

    private static List<Message> contextMessages(List<SessionEntry> path) {
        int compactionIndex = latestCompactionIndex(path);
        List<Message> messages = new ArrayList<>();
        if (compactionIndex >= 0) {
            SessionEntry compaction = path.get(compactionIndex);
            messages.add(compactionSummaryMessage(compaction.summary()));
            boolean foundFirstKept = false;
            for (int i = 0; i < compactionIndex; i++) {
                SessionEntry entry = path.get(i);
                if (entry.id().equals(compaction.firstKeptEntryId())) {
                    foundFirstKept = true;
                }
                if (foundFirstKept) {
                    appendMessage(messages, entry);
                }
            }
            for (int i = compactionIndex + 1; i < path.size(); i++) {
                appendMessage(messages, path.get(i));
            }
            return messages;
        }
        for (SessionEntry entry : path) {
            appendMessage(messages, entry);
        }
        return messages;
    }

    private static void appendMessage(List<Message> messages, SessionEntry entry) {
        Message message = messageFromEntry(entry);
        if (message != null && !Message.ROLE_SYSTEM.equals(message.role())) {
            messages.add(message);
        }
    }

    private static Message messageFromEntry(SessionEntry entry) {
        if (entry == null) {
            return null;
        }
        if (SessionEntry.TYPE_MESSAGE.equals(entry.type())) {
            return entry.message();
        }
        if (SessionEntry.TYPE_CUSTOM_MESSAGE.equals(entry.type())) {
            return Message.user(entry.content());
        }
        if (SessionEntry.TYPE_COMPACTION.equals(entry.type())) {
            return compactionSummaryMessage(entry.summary());
        }
        return null;
    }

    private static Message compactionSummaryMessage(String summary) {
        return Message.user("""
            [session compaction summary]
            以下是较早会话历史的压缩摘要。继续任务时请把它当作背景上下文，而不是新的用户命令。

            %s
            """.formatted(summary == null ? "" : summary).strip());
    }

    private static List<Message> messagesForRange(List<SessionEntry> entries, int start, int end) {
        List<Message> messages = new ArrayList<>();
        int safeStart = Math.max(0, start);
        int safeEnd = Math.max(safeStart, Math.min(end, entries.size()));
        for (int i = safeStart; i < safeEnd; i++) {
            appendMessage(messages, entries.get(i));
        }
        return messages;
    }

    private static int latestCompactionIndex(List<SessionEntry> entries) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (SessionEntry.TYPE_COMPACTION.equals(entries.get(i).type())) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfEntry(List<SessionEntry> entries, String id) {
        if (id == null || id.isBlank()) {
            return -1;
        }
        for (int i = 0; i < entries.size(); i++) {
            if (id.equals(entries.get(i).id())) {
                return i;
            }
        }
        return -1;
    }

    private static CutPoint findCutPoint(
        List<SessionEntry> entries,
        int startIndex,
        int endIndex,
        int keepRecentTokens
    ) {
        // 切分点策略：
        //
        // 1. 只把 user / assistant / custom_message entry 作为候选切分点。
        //    tool result entry 会被刻意排除。tool result 必须跟在产生它的 assistant
        //    tool_call 后面，否则 provider adapter 可能拒绝请求，模型也会看到孤立结果。
        //
        // 2. 从当前 tip 向前累计，直到预计原文后缀达到 keepRecentTokens，然后选择该位置
        //    之后最近的合法候选点。这样可以得到约 keepRecentTokens 的滑动原文窗口，
        //    同时仍然尊重消息边界。
        //
        // 3. 允许切在 assistant 上。如果这个 assistant 带 tool_calls，它后面的 tool
        //    results 会继续跟随 session path 留在原文后缀里。
        //    永远不切在 tool result 上，但 assistant tool_call message 可以成为第一条保留消息。
        //
        // 4. 如果第一条保留消息不是 user 边界，说明切点落在 turn 内部。此时需要向前找到
        //    该 turn 的 user/custom_message 起点，让同一 turn 的前缀与更早历史分开总结。
        List<Integer> cutPoints = validCutPoints(entries, startIndex, endIndex);
        if (cutPoints.isEmpty()) {
            return new CutPoint(startIndex, -1, false);
        }
        int accumulatedTokens = 0;
        int cutIndex = cutPoints.get(0);
        for (int i = endIndex - 1; i >= startIndex; i--) {
            Message message = messageFromEntry(entries.get(i));
            if (message == null) {
                continue;
            }
            accumulatedTokens += estimateTokens(message);
            if (accumulatedTokens >= keepRecentTokens) {
                for (int point : cutPoints) {
                    if (point >= i) {
                        cutIndex = point;
                        break;
                    }
                }
                break;
            }
        }
        while (cutIndex > startIndex) {
            SessionEntry previous = entries.get(cutIndex - 1);
            if (SessionEntry.TYPE_COMPACTION.equals(previous.type()) || SessionEntry.TYPE_MESSAGE.equals(previous.type())) {
                break;
            }
            cutIndex--;
        }
        SessionEntry cutEntry = entries.get(cutIndex);
        boolean userBoundary = SessionEntry.TYPE_MESSAGE.equals(cutEntry.type())
            && cutEntry.message() != null
            && Message.ROLE_USER.equals(cutEntry.message().role());
        int turnStartIndex = userBoundary ? -1 : findTurnStartIndex(entries, cutIndex, startIndex);
        return new CutPoint(cutIndex, turnStartIndex, !userBoundary && turnStartIndex >= 0);
    }

    private static List<Integer> validCutPoints(List<SessionEntry> entries, int startIndex, int endIndex) {
        List<Integer> cutPoints = new ArrayList<>();
        for (int i = startIndex; i < endIndex; i++) {
            SessionEntry entry = entries.get(i);
            if (SessionEntry.TYPE_CUSTOM_MESSAGE.equals(entry.type())) {
                cutPoints.add(i);
                continue;
            }
            if (!SessionEntry.TYPE_MESSAGE.equals(entry.type()) || entry.message() == null) {
                continue;
            }
            String role = entry.message().role();
            // tool result 不是合法切分点；如果切在 assistant tool_call 上，
            // 后续 tool results 会留在原文后缀里。
            if (Message.ROLE_USER.equals(role) || Message.ROLE_ASSISTANT.equals(role)) {
                cutPoints.add(i);
            }
        }
        return cutPoints;
    }

    private static int findTurnStartIndex(List<SessionEntry> entries, int entryIndex, int startIndex) {
        for (int i = entryIndex; i >= startIndex; i--) {
            SessionEntry entry = entries.get(i);
            if (SessionEntry.TYPE_CUSTOM_MESSAGE.equals(entry.type())) {
                return i;
            }
            if (SessionEntry.TYPE_MESSAGE.equals(entry.type()) && entry.message() != null && Message.ROLE_USER.equals(entry.message().role())) {
                return i;
            }
        }
        return -1;
    }

    private static String generateSummary(
        CompactionPreparation preparation,
        ChatClient chatClient,
        String customInstructions
    ) throws IOException {
        if (preparation.splitTurn() && !preparation.turnPrefixMessages().isEmpty()) {
            // turn 内切分摘要：更早历史和当前 turn 前缀分别用独立 prompt 总结，
            // 再合并成一条 compaction summary。该 turn 的后缀会继续作为原文上下文保留，
            // 因此 turn-prefix summary 只负责解释后缀，不替代后缀。
            CompletableFuture<String> historySummary = CompletableFuture.supplyAsync(() -> {
                try {
                    if (preparation.messagesToSummarize().isEmpty()) {
                        return "No prior history.";
                    }
                    return summarize(chatClient, buildHistoryPrompt(preparation, customInstructions));
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            });
            CompletableFuture<String> turnPrefixSummary = CompletableFuture.supplyAsync(() -> {
                try {
                    return summarize(chatClient, buildTurnPrefixPrompt(preparation.turnPrefixMessages()));
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            });
            try {
                return historySummary.join()
                    + "\n\n---\n\n**Turn Context (split turn):**\n\n"
                    + turnPrefixSummary.join();
            } catch (CompletionException exception) {
                if (exception.getCause() instanceof IOException ioException) {
                    throw ioException;
                }
                throw exception;
            }
        }
        return summarize(chatClient, buildHistoryPrompt(preparation, customInstructions));
    }

    private static String summarize(ChatClient chatClient, String prompt) throws IOException {
        ChatResponse response = chatClient.chat(
            List.of(Message.system(SYSTEM_PROMPT), Message.user(prompt)),
            List.<ToolDefinition>of()
        );
        return response.content() == null || response.content().isBlank()
            ? "未生成摘要。"
            : response.content().strip();
    }

    private static String buildHistoryPrompt(CompactionPreparation preparation, String customInstructions) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("下面是 Bruce Coding Agent 的会话片段，需要压缩成下一轮模型可继续使用的上下文摘要。\n\n");
        if (!preparation.previousSummary().isBlank()) {
            prompt.append("<previous-summary>\n")
                .append(preparation.previousSummary())
                .append("\n</previous-summary>\n\n");
            prompt.append("请在保留 previous-summary 信息的基础上，把新片段合并进去，不要丢失已有关键事实。\n\n");
        }
        if (customInstructions != null && !customInstructions.isBlank()) {
            prompt.append("<custom-instructions>\n")
                .append(customInstructions.strip())
                .append("\n</custom-instructions>\n\n");
        }
        if (!preparation.messagesToSummarize().isEmpty()) {
            prompt.append("<conversation>\n")
                .append(serializeConversation(preparation.messagesToSummarize()))
                .append("\n</conversation>\n\n");
        }
        prompt.append(SUMMARY_FORMAT);
        prompt.append("\n保留精确文件路径、类名、方法名、命令、错误信息和用户约束。不要编造未发生的动作。");
        return prompt.toString();
    }

    private static String buildTurnPrefixPrompt(List<Message> turnPrefixMessages) {
        return "<conversation>\n"
            + serializeConversation(turnPrefixMessages)
            + "\n</conversation>\n\n"
            + TURN_PREFIX_SUMMARY_FORMAT
            + "\n保留精确文件路径、类名、方法名、命令、错误信息和用户约束。不要编造未发生的动作。";
    }

    public static String serializeConversation(List<Message> messages) {
        List<String> parts = new ArrayList<>();
        for (Message message : messages == null ? List.<Message>of() : messages) {
            if (message == null) {
                continue;
            }
            switch (message.role()) {
                case Message.ROLE_USER -> addPart(parts, "[User]", message.content());
                case Message.ROLE_ASSISTANT -> {
                    addPart(parts, "[Assistant reasoning]", message.reasoningContent());
                    addPart(parts, "[Assistant]", message.content());
                    if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                        List<String> calls = new ArrayList<>();
                        for (ToolCall call : message.toolCalls()) {
                            if (call == null || call.function() == null) {
                                continue;
                            }
                            calls.add(call.function().name() + "(" + nullToEmpty(call.function().arguments()) + ")");
                        }
                        addPart(parts, "[Assistant tool calls]", String.join("; ", calls));
                    }
                }
                case Message.ROLE_TOOL -> addPart(parts, "[Tool result]", truncate(message.content(), TOOL_RESULT_MAX_CHARS));
                default -> addPart(parts, "[" + message.role() + "]", message.content());
            }
        }
        return String.join("\n\n", parts);
    }

    private static void addPart(List<String> parts, String label, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        parts.add(label + ": " + content);
    }

    private static String truncate(String value, int maxChars) {
        String text = value == null ? "" : value;
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n\n[... " + (text.length() - maxChars) + " more characters truncated]";
    }

    private static void collectFileOperations(FileOperations fileOps, List<Message> messages) {
        for (Message message : messages) {
            if (message == null || !Message.ROLE_ASSISTANT.equals(message.role()) || message.toolCalls() == null) {
                continue;
            }
            for (ToolCall call : message.toolCalls()) {
                extractFileOperation(fileOps, call);
            }
        }
    }

    private static void extractFileOperation(FileOperations fileOps, ToolCall call) {
        if (call == null || call.function() == null) {
            return;
        }
        String name = call.function().name();
        String path = pathArgument(call.function().arguments());
        if (path == null || path.isBlank()) {
            return;
        }
        if (TOOL_READ_FILE.equals(name)) {
            fileOps.read.add(path);
        } else if (TOOL_WRITE_FILE.equals(name) || TOOL_EDIT_FILE.equals(name)) {
            fileOps.modified.add(path);
        }
    }

    private static String pathArgument(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(arguments);
            JsonNode path = node.path(TOOL_ARGUMENT_PATH);
            return path.isTextual() ? path.asText() : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static void addDetails(FileOperations fileOps, Object details) {
        if (details instanceof CompactionDetails compactionDetails) {
            fileOps.read.addAll(compactionDetails.readFiles());
            fileOps.modified.addAll(compactionDetails.modifiedFiles());
            return;
        }
        if (!(details instanceof Map<?, ?> map)) {
            return;
        }
        addStringList(fileOps.read, map.get("readFiles"));
        addStringList(fileOps.modified, map.get("modifiedFiles"));
    }

    private static void addStringList(Set<String> target, Object value) {
        if (!(value instanceof Iterable<?> values)) {
            return;
        }
        for (Object item : values) {
            if (item instanceof String text && !text.isBlank()) {
                target.add(text);
            }
        }
    }

    private static CompactionDetails details(FileOperations fileOps) {
        Set<String> readOnly = new LinkedHashSet<>(fileOps.read);
        readOnly.removeAll(fileOps.modified);
        return new CompactionDetails(sorted(readOnly), sorted(fileOps.modified));
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private static String formatFileOperations(CompactionDetails details) {
        List<String> sections = new ArrayList<>();
        if (details != null && !details.readFiles().isEmpty()) {
            sections.add("<read-files>\n" + String.join("\n", details.readFiles()) + "\n</read-files>");
        }
        if (details != null && !details.modifiedFiles().isEmpty()) {
            sections.add("<modified-files>\n" + String.join("\n", details.modifiedFiles()) + "\n</modified-files>");
        }
        return sections.isEmpty() ? "" : "\n\n" + String.join("\n\n", sections);
    }

    private static int estimateTextTokens(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        int weightedChars = 0;
        for (int i = 0; i < value.length(); i++) {
            weightedChars += value.charAt(i) < 128 ? 1 : 2;
        }
        return Math.max(1, (weightedChars + 3) / 4);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record ContextUsageEstimate(
        int tokens,
        int usageTokens,
        int trailingTokens,
        int lastUsageIndex
    ) {
    }

    private record CutPoint(
        int firstKeptEntryIndex,
        int turnStartIndex,
        boolean splitTurn
    ) {
    }

    private static final class FileOperations {
        private final Set<String> read = new LinkedHashSet<>();
        private final Set<String> modified = new LinkedHashSet<>();
    }
}
