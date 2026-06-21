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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * DeepSeek 的 OpenAI-compatible Chat Completion 客户端。
 *
 * <p>这个类故意不引入官方 SDK，而是直接用 OkHttp 拼 HTTP 请求。
 * 对学习项目来说，这样可以清楚看到 tools、tool_calls、messages 是如何落到 JSON 请求体里的。</p>
 *
 * <p>带工具定义的首轮请求示例，对应 {@link #chat(List, List)} 里构造 requestBody 的逻辑：</p>
 * <pre>{@code
 * {
 *   "model": "deepseek-v4-flash",
 *   "stream": false,
 *   "thinking": {
 *     "type": "disabled"
 *   },
 *   "messages": [
 *     {
 *       "role": "system",
 *       "content": "你是一个智能编程助手..."
 *     },
 *     {
 *       "role": "user",
 *       "content": "查看当前目录名"
 *     }
 *   ],
 *   "tools": [
 *     {
 *       "type": "function",
 *       "function": {
 *         "name": "execute_command",
 *         "description": "在工作目录内执行 Shell 命令，用于编译、运行、Git 操作等",
 *         "parameters": {
 *           "type": "object",
 *           "properties": {
 *             "command": {
 *               "type": "string",
 *               "description": "要执行的命令"
 *             }
 *           },
 *           "required": ["command"]
 *         }
 *       }
 *     }
 *   ],
 *   "tool_choice": "auto"
 * }
 * }</pre>
 *
 * <p>模型决定调用工具时的响应示例，对应 {@link #parseResponse(String)} 中解析 tool_calls 的逻辑：</p>
 * <pre>{@code
 * {
 *   "id": "chatcmpl-example",
 *   "object": "chat.completion",
 *   "choices": [
 *     {
 *       "index": 0,
 *       "message": {
 *         "role": "assistant",
 *         "content": "",
 *         "tool_calls": [
 *           {
 *             "id": "call_pwd_001",
 *             "type": "function",
 *             "function": {
 *               "name": "execute_command",
 *               "arguments": "{\"command\":\"pwd\"}"
 *             }
 *           }
 *         ]
 *       },
 *       "finish_reason": "tool_calls"
 *     }
 *   ],
 *   "usage": {
 *     "prompt_tokens": 120,
 *     "completion_tokens": 24,
 *     "total_tokens": 144
 *   }
 * }
 * }</pre>
 *
 * <p>工具执行完成后，Agent 下一轮会把 assistant 的 tool_calls 和 tool 结果一起发回模型。
 * 这个片段对应本类序列化 Message.toolCalls() 和 Message.toolCallId() 的逻辑：</p>
 * <pre>{@code
 * {
 *   "messages": [
 *     {
 *       "role": "assistant",
 *       "content": "",
 *       "tool_calls": [
 *         {
 *           "id": "call_pwd_001",
 *           "type": "function",
 *           "function": {
 *             "name": "execute_command",
 *             "arguments": "{\"command\":\"pwd\"}"
 *           }
 *         }
 *       ]
 *     },
 *     {
 *       "role": "tool",
 *       "tool_call_id": "call_pwd_001",
 *       "content": "命令执行完成 (exit code: 0)\n/workspace/bruce-cli\n"
 *     }
 *   ]
 * }
 * }</pre>
 */
public class DeepSeekClient implements ChatClient {
    // DeepSeek 的 OpenAI-compatible endpoint，完整请求地址是 base URL + /chat/completions。
    private static final String API_URL = "https://api.deepseek.com/chat/completions";

    // 默认使用轻量模型，适合学习和频繁调试；也可以通过 DEEPSEEK_MODEL 覆盖。
    private static final String DEFAULT_MODEL = "deepseek-v4-flash";
    private static final MediaType JSON = MediaType.parse("application/json");

    private final String apiKey;
    private final String model;
    private final OkHttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 真实运行时使用的构造方法。
     *
     * @param apiKey DeepSeek API Key
     * @param model 模型名，为空时回退到 DEFAULT_MODEL
     */
    public DeepSeekClient(String apiKey, String model) {
        this(apiKey, model, new OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build());
    }

    /**
     * 包级构造方法主要给测试用，方便替换 OkHttpClient。
     */
    DeepSeekClient(String apiKey, String model, OkHttpClient httpClient) {
        this.apiKey = apiKey;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model;
        this.httpClient = httpClient;
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<ToolDefinition> tools) throws IOException {
        // 1. 构造请求体的顶层字段。
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("stream", false);

        // Tool Call 学习场景需要模型直接给出工具调用或最终回答，所以关闭思考模式。
        requestBody.putObject("thinking").put("type", "disabled");

        // 2. 序列化历史消息。ReAct 循环依赖完整历史，不能只发送最后一条用户消息。
        ArrayNode messagesArray = requestBody.putArray("messages");
        for (Message msg : messages) {
            ObjectNode msgNode = messagesArray.addObject();
            msgNode.put("role", msg.role());

            // 有些 assistant 消息只包含 tool_calls，content 可能为空或 null。
            if (msg.content() == null) {
                msgNode.putNull("content");
            } else {
                msgNode.put("content", msg.content());
            }

            // assistant 消息如果带有工具调用，需要按 API 要求原样放回历史。
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                ArrayNode toolCallsArray = msgNode.putArray("tool_calls");
                for (ToolCall toolCall : msg.toolCalls()) {
                    ObjectNode toolCallNode = toolCallsArray.addObject();
                    toolCallNode.put("id", toolCall.id());
                    toolCallNode.put("type", "function");
                    ObjectNode functionNode = toolCallNode.putObject("function");
                    functionNode.put("name", toolCall.function().name());
                    functionNode.put("arguments", toolCall.function().arguments());
                }
            }

            // tool 消息必须带 tool_call_id，模型才能把工具返回值关联到刚才的调用。
            if (msg.toolCallId() != null) {
                msgNode.put("tool_call_id", msg.toolCallId());
            }
        }

        // 3. 序列化工具定义。模型会根据 description 和 parameters 决定是否调用工具。
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = requestBody.putArray("tools");
            for (ToolDefinition tool : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode functionNode = toolNode.putObject("function");
                functionNode.put("name", tool.name());
                functionNode.put("description", tool.description());
                functionNode.set("parameters", tool.parameters());
            }

            // auto 表示让模型自行决定“直接回答”还是“发起工具调用”。
            requestBody.put("tool_choice", "auto");
        }

        // 4. 发送 HTTP 请求。Authorization 使用 Bearer Token。
        Request request = new Request.Builder()
            .url(API_URL)
            .header("Authorization", "Bearer " + apiKey)
            .post(RequestBody.create(requestBody.toString(), JSON))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("DeepSeek API request failed: HTTP " + response.code() + "\n" + responseBody);
            }

            // 5. 把厂商响应解析成项目内部的 ChatResponse，Agent 层就不用关心原始 JSON 细节。
            return parseResponse(responseBody);
        }
    }

    private ChatResponse parseResponse(String responseBody) throws IOException {
        JsonNode root = mapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IOException("DeepSeek API response has no choices: " + responseBody);
        }

        // Chat Completion 常规响应结构：choices[0].message。
        JsonNode message = choices.get(0).path("message");
        String content = message.path("content").isMissingNode() || message.path("content").isNull()
            ? ""
            : message.path("content").asText();

        // 如果模型决定调用工具，tool_calls 会出现在 assistant message 上。
        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode toolCallsNode = message.path("tool_calls");
        if (toolCallsNode.isArray()) {
            for (JsonNode toolCallNode : toolCallsNode) {
                JsonNode functionNode = toolCallNode.path("function");
                toolCalls.add(new ToolCall(
                    toolCallNode.path("id").asText(),
                    new FunctionCall(
                        functionNode.path("name").asText(),
                        functionNode.path("arguments").asText("{}")
                    )
                ));
            }
        }

        return new ChatResponse(content, toolCalls);
    }
}
