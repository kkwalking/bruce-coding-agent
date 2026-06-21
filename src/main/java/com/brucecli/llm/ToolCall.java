package com.brucecli.llm;

/**
 * 模型返回的单个工具调用。
 *
 * @param id 本次工具调用的唯一 ID，工具结果消息需要带回同一个 ID
 * @param function 具体要执行的函数名和参数
 */
public record ToolCall(String id, FunctionCall function) {
}
