package com.brucecli.approval;

import java.util.Set;

/**
 * 静态危险策略：用确定规则判断哪些工具调用必须先经过人工审批。
 */
public final class ApprovalPolicy {
    private static final Set<String> DANGEROUS_TOOLS = Set.of(
        "write_file",
        "execute_command",
        "create_project"
    );

    private ApprovalPolicy() {
    }

    public static boolean requiresApproval(String toolName) {
        return DANGEROUS_TOOLS.contains(toolName) || isMcpTool(toolName);
    }

    public static String getDangerLevel(String toolName) {
        if (isMcpTool(toolName)) {
            return "🟡 中危";
        }
        return switch (toolName) {
            case "execute_command" -> "🔴 高危";
            case "write_file" -> "🟡 中危";
            case "create_project" -> "🟡 中危";
            default -> "🟢 安全";
        };
    }

    public static String getRiskDescription(String toolName) {
        if (isMcpTool(toolName)) {
            return "将调用第三方 MCP 工具，可能访问本地或远程资源";
        }
        return switch (toolName) {
            case "execute_command" -> "将在系统上执行 Shell 命令，可能修改文件、安装软件或影响系统状态";
            case "write_file" -> "将写入或覆盖文件内容，原有内容将丢失";
            case "create_project" -> "将在磁盘上创建新目录和文件";
            default -> "安全的只读操作";
        };
    }

    private static boolean isMcpTool(String toolName) {
        return toolName != null && toolName.startsWith("mcp__");
    }
}
