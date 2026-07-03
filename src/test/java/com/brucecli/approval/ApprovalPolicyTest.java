package com.brucecli.approval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalPolicyTest {
    @Test
    void requiresApprovalForDangerousToolsOnly() {
        assertTrue(ApprovalPolicy.requiresApproval("edit_file"));
        assertTrue(ApprovalPolicy.requiresApproval("write_file"));
        assertTrue(ApprovalPolicy.requiresApproval("execute_command"));
        assertTrue(ApprovalPolicy.requiresApproval("mcp__filesystem__read_file"));

        assertFalse(ApprovalPolicy.requiresApproval("read_file"));
        assertFalse(ApprovalPolicy.requiresApproval("search_code"));
    }

    @Test
    void describesEditFileRiskAsFileModification() {
        assertTrue(ApprovalPolicy.getRiskDescription("edit_file").contains("修改文件"));
        assertTrue(ApprovalPolicy.getDangerLevel("edit_file").contains("中危"));
    }
}
