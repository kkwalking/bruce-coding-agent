package com.brucecli.llm;

/**
 * tool_calls[i].function 的内容。
 *
 * @param name 模型想调用的工具名，例如 read_file 或 write_file
 * @param arguments JSON 字符串，里面是调用该工具所需的参数
 */
public record FunctionCall(String name, String arguments) {
}
