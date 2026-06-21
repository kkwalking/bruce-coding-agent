package com.brucecli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.brucecli.llm.ToolDefinition;

/**
 * 项目内部的工具对象。
 *
 * <p>它比发给模型的 ToolDefinition 多了 executor：
 * ToolDefinition 是“告诉模型怎么调用”，executor 是“Java 里真正怎么执行”。</p>
 */
public record Tool(String name, String description, JsonNode parameters, ToolExecutor executor) {
    /**
     * 转成只包含名称、描述和参数 Schema 的定义，供大模型请求使用。
     */
    public ToolDefinition definition() {
        return new ToolDefinition(name, description, parameters);
    }
}
