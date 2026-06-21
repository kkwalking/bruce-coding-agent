package com.brucecli.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.brucecli.approval.ApprovalPolicy;
import com.brucecli.approval.ApprovalRequest;
import com.brucecli.approval.ApprovalResult;
import com.brucecli.approval.HitlHandler;
import com.brucecli.runtime.ConcurrencyConfig;
import com.brucecli.tool.CommandGuard;
import com.brucecli.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * 集成应用里的统一工具注册表：HITL 审批、命令安全兜底和输出截断都从这里经过。
 */
public class GuardedHitlToolRegistry extends ToolRegistry {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HitlHandler hitlHandler;
    private final CommandGuard commandGuard;
    private final ConcurrencyConfig concurrencyConfig;

    public GuardedHitlToolRegistry(
        HitlHandler hitlHandler,
        Path workspaceRoot,
        CommandGuard commandGuard,
        ConcurrencyConfig concurrencyConfig
    ) {
        super(workspaceRoot);
        this.hitlHandler = Objects.requireNonNull(hitlHandler);
        this.commandGuard = Objects.requireNonNull(commandGuard);
        this.concurrencyConfig = Objects.requireNonNull(concurrencyConfig);
    }

    @Override
    public String executeTool(String name, Map<String, String> args) {
        if ("execute_command".equals(name)) {
            CommandGuard.GuardResult guardResult = commandGuard.check(args == null ? null : args.get("command"));
            if (!guardResult.allowed()) {
                return "命令被安全策略拒绝: " + guardResult.reason();
            }
        }

        ApprovalOutcome approval = approve(name, args);
        if (!approval.approved()) {
            return concurrencyConfig.truncate(approval.message());
        }
        return concurrencyConfig.truncate(super.executeTool(name, approval.arguments()));
    }

    private ApprovalOutcome approve(String name, Map<String, String> args) {
        if (!hitlHandler.isEnabled() || !ApprovalPolicy.requiresApproval(name)) {
            return ApprovalOutcome.approved(args);
        }

        String argumentsJson;
        try {
            argumentsJson = MAPPER.writeValueAsString(args);
        } catch (Exception e) {
            return ApprovalOutcome.rejected("工具参数解析失败: " + e.getMessage());
        }

        ApprovalResult result = hitlHandler.requestApproval(ApprovalRequest.of(name, argumentsJson, null));
        if (result == null) {
            return ApprovalOutcome.rejected("[HITL] 操作已被拒绝：审批器没有返回结果");
        }
        if (result.isRejected()) {
            String reason = result.reason() == null ? "用户拒绝了此操作" : result.reason();
            return ApprovalOutcome.rejected("[HITL] 操作已被拒绝：" + reason);
        }
        if (result.isSkipped()) {
            return ApprovalOutcome.rejected("[HITL] 操作已被跳过");
        }
        if (result.decision() != ApprovalResult.Decision.MODIFIED) {
            return ApprovalOutcome.approved(args);
        }

        try {
            Map<String, String> modified = MAPPER.readValue(
                result.effectiveArguments(argumentsJson),
                new TypeReference<Map<String, String>>() {
                }
            );
            return ApprovalOutcome.approved(modified);
        } catch (Exception e) {
            return ApprovalOutcome.rejected("工具参数解析失败: " + e.getMessage());
        }
    }

    private record ApprovalOutcome(Map<String, String> arguments, String message) {
        private static ApprovalOutcome approved(Map<String, String> arguments) {
            return new ApprovalOutcome(arguments, null);
        }

        private static ApprovalOutcome rejected(String message) {
            return new ApprovalOutcome(null, message);
        }

        private boolean approved() {
            return message == null;
        }
    }
}
