package com.brucecli.approval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalPolicyTest {
    @Test
    void requiresApprovalForDangerousToolsOnly() {
        assertTrue(ApprovalPolicy.requiresApproval("write_file"));
        assertTrue(ApprovalPolicy.requiresApproval("execute_command"));
        assertTrue(ApprovalPolicy.requiresApproval("create_project"));

        assertFalse(ApprovalPolicy.requiresApproval("read_file"));
        assertFalse(ApprovalPolicy.requiresApproval("list_dir"));
        assertFalse(ApprovalPolicy.requiresApproval("search_code"));
    }
}
