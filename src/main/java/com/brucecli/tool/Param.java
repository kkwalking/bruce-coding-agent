package com.brucecli.tool;

/**
 * 工具参数的简化描述。
 *
 * <p>ToolRegistry 会把 Param 转成 JSON Schema 中 properties 和 required 字段。</p>
 */
public record Param(String name, String type, String description, boolean required) {
}
