package com.brucecli.approval;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashSet;
import java.util.Set;

/**
 * 终端版 HITL 审批器。
 */
public class TerminalHitlHandler implements HitlHandler {
    private final BufferedReader in;
    private final PrintStream out;
    private final Set<String> approvedAllTools = new HashSet<>();
    private boolean enabled;

    public TerminalHitlHandler(boolean enabled, BufferedReader in, PrintStream out) {
        this.enabled = enabled;
        this.in = in;
        this.out = out;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public synchronized ApprovalResult requestApproval(ApprovalRequest request) {
        if (!enabled) {
            return ApprovalResult.approve();
        }
        if (approvedAllTools.contains(request.toolName())) {
            out.println("  [HITL] " + request.toolName() + " 已在本次会话中全部放行，自动通过");
            return ApprovalResult.approveAll();
        }

        out.println();
        out.println("────────── ⚠️  HITL 审批请求 ──────────");
        out.println(request.toDisplayText());

        return promptUntilDecision(request);
    }

    @Override
    public synchronized void clearApprovedAll() {
        approvedAllTools.clear();
    }

    private ApprovalResult promptUntilDecision(ApprovalRequest request) {
        for (int attempt = 0; attempt < 5; attempt++) {
            out.println("请选择操作：[y/Enter] 批准  [a] 全部放行  [n] 拒绝  [s] 跳过  [m] 修改参数");
            out.print("> ");
            out.flush();

            String input = readLine();
            if (input == null) {
                return ApprovalResult.reject("输入流已结束");
            }
            String normalized = input.trim().toLowerCase();

            if (normalized.isEmpty() || normalized.equals("y")) {
                return ApprovalResult.approve();
            }
            switch (normalized) {
                case "a" -> {
                    approvedAllTools.add(request.toolName());
                    return ApprovalResult.approveAll();
                }
                case "n" -> {
                    return ApprovalResult.reject(readOptionalLine("请输入拒绝原因（可选）：", "用户拒绝了此操作"));
                }
                case "s" -> {
                    return ApprovalResult.skip();
                }
                case "m" -> {
                    String modified = readOptionalLine("请输入修改后的 JSON 参数：", null);
                    if (modified == null || modified.isBlank()) {
                        out.println("  修改参数不能为空");
                    } else {
                        return ApprovalResult.modify(modified);
                    }
                }
                default -> out.println("  ❓ 无法识别的选项，请输入 y/a/n/s/m 之一");
            }
        }
        return ApprovalResult.reject("连续多次无效输入");
    }

    private String readOptionalLine(String prompt, String fallback) {
        out.print(prompt);
        out.flush();
        String line = readLine();
        if (line == null || line.isBlank()) {
            return fallback;
        }
        return line.trim();
    }

    private String readLine() {
        try {
            return in.readLine();
        } catch (IOException e) {
            return null;
        }
    }
}
