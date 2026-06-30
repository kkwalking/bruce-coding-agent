package com.brucecli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI-compatible Chat Completion 公共客户端。
 *
 * <p>provider 子类只需要声明 endpoint、模型能力和少量兼容差异；
 * messages/tools/content parts/SSE/tool call delta 合并都集中在这里。</p>
 */
public abstract class OpenAiCompatibleChatClient implements ChatClient {
    private static final MediaType JSON = MediaType.parse("application/json");

    protected final String apiKey;
    protected final String model;
    protected final String apiUrl;
    protected final OkHttpClient httpClient;
    protected final ObjectMapper mapper = new ObjectMapper();

    protected OpenAiCompatibleChatClient(
        String apiKey,
        String model,
        String apiUrl,
        OkHttpClient httpClient
    ) {
        this.apiKey = apiKey;
        this.model = model;
        this.apiUrl = apiUrl;
        this.httpClient = httpClient == null ? defaultHttpClient() : httpClient;
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<ToolDefinition> tools) throws IOException {
        return execute(messages, tools, false, StreamListener.NO_OP);
    }

    @Override
    public ChatResponse chat(
        List<Message> messages,
        List<ToolDefinition> tools,
        StreamListener listener
    ) throws IOException {
        return execute(messages, tools, true, listener == null ? StreamListener.NO_OP : listener);
    }

    protected ChatResponse execute(
        List<Message> messages,
        List<ToolDefinition> tools,
        boolean stream,
        StreamListener listener
    ) throws IOException {
        ObjectNode requestBody = buildRequestBody(messages, tools, stream);
        customizeRequestBody(requestBody, messages, tools, stream);

        Request.Builder requestBuilder = new Request.Builder()
            .url(apiUrl)
            .header("Authorization", "Bearer " + apiKey)
            .post(RequestBody.create(requestBody.toString(), JSON));
        Request request = customizeRequest(requestBuilder).build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody;
            if (response.body() == null) {
                responseBody = "";
            } else if (stream && response.isSuccessful()) {
                return parseStream(response, listener);
            } else {
                responseBody = response.body().string();
            }

            if (!response.isSuccessful()) {
                throw new IOException(getProviderName() + " API request failed: HTTP "
                    + response.code() + "\n" + responseBody);
            }
            return parseResponse(responseBody);
        }
    }

    protected ObjectNode buildRequestBody(
        List<Message> messages,
        List<ToolDefinition> tools,
        boolean stream
    ) {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("stream", stream);
        if (stream && includeUsageInStream()) {
            requestBody.putObject("stream_options").put("include_usage", true);
        }

        ArrayNode messagesArray = requestBody.putArray("messages");
        if (messages != null) {
            for (Message message : messages) {
                messagesArray.add(serializeMessage(message));
            }
        }

        if (tools != null && !tools.isEmpty() && supportsTools()) {
            ArrayNode toolsArray = requestBody.putArray("tools");
            for (ToolDefinition tool : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode functionNode = toolNode.putObject("function");
                functionNode.put("name", tool.name());
                functionNode.put("description", tool.description());
                functionNode.set("parameters", tool.parameters());
            }
            requestBody.put("tool_choice", "auto");
        }

        return requestBody;
    }

    protected ObjectNode serializeMessage(Message message) {
        ObjectNode msgNode = mapper.createObjectNode();
        msgNode.put("role", message.role());
        appendMessageContent(msgNode, message);

        if (shouldSendReasoningContentInRequestHistory()
            && "assistant".equals(message.role())
            && message.reasoningContent() != null
            && !message.reasoningContent().isBlank()) {
            msgNode.put("reasoning_content", message.reasoningContent());
        }

        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            ArrayNode toolCallsArray = msgNode.putArray("tool_calls");
            for (ToolCall toolCall : message.toolCalls()) {
                ObjectNode toolCallNode = toolCallsArray.addObject();
                toolCallNode.put("id", toolCall.id());
                toolCallNode.put("type", "function");
                ObjectNode functionNode = toolCallNode.putObject("function");
                functionNode.put("name", toolCall.function().name());
                functionNode.put("arguments", toolCall.function().arguments());
            }
        }

        if (message.toolCallId() != null) {
            msgNode.put("tool_call_id", message.toolCallId());
        }
        return msgNode;
    }

    protected void appendMessageContent(ObjectNode msgNode, Message message) {
        if (!message.hasContentParts()) {
            if (message.content() == null) {
                msgNode.putNull("content");
            } else {
                msgNode.put("content", message.content());
            }
            return;
        }

        ArrayNode contentArray = msgNode.putArray("content");
        for (ContentPart part : message.contentParts()) {
            if (part == null) {
                continue;
            }
            ObjectNode partNode = contentArray.addObject();
            if (part.isTextPart()) {
                partNode.put("type", "text");
                partNode.put("text", part.text() == null ? "" : part.text());
            } else if (part.isImagePart()) {
                partNode.put("type", "image_url");
                partNode.putObject("image_url").put("url", toImageUrl(part));
            } else {
                partNode.put("type", "text");
                partNode.put("text", part.fallbackText());
            }
        }
    }

    protected ChatResponse parseResponse(String responseBody) throws IOException {
        JsonNode root = mapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.size() == 0) {
            throw new IOException(getProviderName() + " API response has no choices: " + responseBody);
        }

        JsonNode message = choices.get(0).path("message");
        Usage usage = parseUsage(root.path("usage"));
        return new ChatResponse(
            textField(message, "role", "assistant"),
            textField(message, "content", ""),
            firstTextField(message, "reasoning_content", "reasoning", "reasoningContent"),
            parseToolCalls(message.path("tool_calls")),
            usage.inputTokens,
            usage.outputTokens,
            usage.cachedInputTokens
        );
    }

    protected ChatResponse parseStream(Response response, StreamListener listener) throws IOException {
        StreamAccumulator accumulator = new StreamAccumulator();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            response.body().byteStream(),
            StandardCharsets.UTF_8
        ))) {
            String line;
            StringBuilder data = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    if (!data.isEmpty() && parseStreamEvent(data.toString(), accumulator, listener)) {
                        break;
                    }
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring("data:".length()).trim());
                }
            }
            if (!data.isEmpty()) {
                parseStreamEvent(data.toString(), accumulator, listener);
            }
        }
        return accumulator.toResponse();
    }

    protected boolean parseStreamEvent(
        String data,
        StreamAccumulator accumulator,
        StreamListener listener
    ) throws IOException {
        if (data == null || data.isBlank()) {
            return false;
        }
        if ("[DONE]".equals(data.trim())) {
            return true;
        }

        JsonNode root = mapper.readTree(data);
        Usage usage = parseUsage(root.path("usage"));
        accumulator.mergeUsage(usage);

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.size() == 0) {
            return false;
        }

        JsonNode delta = choices.get(0).path("delta");
        if (delta.isMissingNode()) {
            delta = choices.get(0).path("message");
        }
        String role = textField(delta, "role", "");
        if (!role.isBlank()) {
            accumulator.role = role;
        }

        String reasoningDelta = firstTextField(delta, "reasoning_content", "reasoning", "reasoningContent");
        if (!reasoningDelta.isEmpty()) {
            accumulator.reasoning.append(reasoningDelta);
            listener.onReasoningDelta(reasoningDelta);
        }

        String contentDelta = textField(delta, "content", "");
        if (!contentDelta.isEmpty()) {
            accumulator.content.append(contentDelta);
            listener.onContentDelta(contentDelta);
        }

        JsonNode toolCalls = delta.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode toolCallDelta : toolCalls) {
                accumulator.mergeToolCallDelta(toolCallDelta);
            }
        }

        return false;
    }

    protected List<ToolCall> parseToolCalls(JsonNode toolCallsNode) {
        if (!toolCallsNode.isArray()) {
            return List.of();
        }
        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode toolCallNode : toolCallsNode) {
            JsonNode functionNode = toolCallNode.path("function");
            toolCalls.add(new ToolCall(
                textField(toolCallNode, "id", ""),
                new FunctionCall(
                    textField(functionNode, "name", ""),
                    textField(functionNode, "arguments", "{}")
                )
            ));
        }
        return toolCalls;
    }

    protected Usage parseUsage(JsonNode usageNode) {
        if (usageNode == null || usageNode.isMissingNode() || usageNode.isNull()) {
            return Usage.empty();
        }
        int inputTokens = intField(usageNode, "prompt_tokens", "input_tokens");
        int outputTokens = intField(usageNode, "completion_tokens", "output_tokens");
        int cachedInputTokens = intField(usageNode, "cached_input_tokens");
        JsonNode promptDetails = usageNode.path("prompt_tokens_details");
        if (cachedInputTokens == 0 && promptDetails.isObject()) {
            cachedInputTokens = intField(promptDetails, "cached_tokens");
        }
        JsonNode inputDetails = usageNode.path("input_token_details");
        if (cachedInputTokens == 0 && inputDetails.isObject()) {
            cachedInputTokens = intField(inputDetails, "cached_tokens");
        }
        return new Usage(inputTokens, outputTokens, cachedInputTokens);
    }

    protected void customizeRequestBody(
        ObjectNode requestBody,
        List<Message> messages,
        List<ToolDefinition> tools,
        boolean stream
    ) {
    }

    protected Request.Builder customizeRequest(Request.Builder requestBuilder) {
        return requestBuilder;
    }

    protected boolean shouldSendReasoningContentInRequestHistory() {
        return false;
    }

    protected boolean includeUsageInStream() {
        return true;
    }

    protected String toImageUrl(ContentPart part) {
        return part.imageUrl() == null ? "" : part.imageUrl().url();
    }

    protected static String toChatCompletionsUrl(String value) {
        String trimmed = trimSlash(value.trim());
        if (trimmed.endsWith("/chat/completions")) {
            return trimmed;
        }
        return trimmed + "/chat/completions";
    }

    protected static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    protected static OkHttpClient defaultHttpClient() {
        return new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();
    }

    protected static String textField(JsonNode node, String fieldName, String defaultValue) {
        JsonNode value = node.path(fieldName);
        if (value.isMissingNode() || value.isNull()) {
            return defaultValue;
        }
        return value.asText(defaultValue);
    }

    protected static String firstTextField(JsonNode node, String... fieldNames) {
        if (fieldNames == null) {
            return "";
        }
        for (String fieldName : fieldNames) {
            String value = textField(node, fieldName, "");
            if (!value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static int intField(JsonNode node, String... fieldNames) {
        if (fieldNames == null) {
            return 0;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.path(fieldName);
            if (value.isNumber()) {
                return value.asInt();
            }
        }
        return 0;
    }

    protected record Usage(int inputTokens, int outputTokens, int cachedInputTokens) {
        static Usage empty() {
            return new Usage(0, 0, 0);
        }
    }

    protected static final class StreamAccumulator {
        private String role = "assistant";
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final Map<Integer, ToolCallDelta> toolCalls = new HashMap<>();
        private Usage usage = Usage.empty();

        void mergeUsage(Usage next) {
            if (next == null) {
                return;
            }
            if (next.inputTokens() > 0 || next.outputTokens() > 0 || next.cachedInputTokens() > 0) {
                usage = next;
            }
        }

        void mergeToolCallDelta(JsonNode node) {
            int index = node.path("index").asInt(toolCalls.size());
            ToolCallDelta delta = toolCalls.computeIfAbsent(index, ToolCallDelta::new);
            String id = textField(node, "id", "");
            if (!id.isBlank()) {
                delta.id = id;
            }
            JsonNode function = node.path("function");
            String name = textField(function, "name", "");
            if (!name.isBlank()) {
                delta.name.append(name);
            }
            String arguments = textField(function, "arguments", "");
            if (!arguments.isEmpty()) {
                delta.arguments.append(arguments);
            }
        }

        ChatResponse toResponse() {
            List<ToolCall> calls = toolCalls.values().stream()
                .sorted(Comparator.comparingInt(delta -> delta.index))
                .map(ToolCallDelta::toToolCall)
                .toList();
            return new ChatResponse(
                role,
                content.toString(),
                reasoning.toString(),
                calls,
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cachedInputTokens()
            );
        }
    }

    protected static final class ToolCallDelta {
        private final int index;
        private String id = "";
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();

        ToolCallDelta(int index) {
            this.index = index;
        }

        ToolCall toToolCall() {
            return new ToolCall(
                id,
                new FunctionCall(
                    name.toString(),
                    arguments.isEmpty() ? "{}" : arguments.toString()
                )
            );
        }
    }
}
