package com.brucecli.tool;

import java.util.Map;

/**
 * 工具的实际执行逻辑。
 *
 * <p>参数来自模型生成的 arguments JSON，ToolRegistry 会先把它解析成 Map 再传进来。</p>
 */
@FunctionalInterface
public interface ToolExecutor {
    String execute(Map<String, String> args) throws Exception;
}
