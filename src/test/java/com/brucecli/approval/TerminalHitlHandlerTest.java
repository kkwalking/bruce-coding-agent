package com.brucecli.approval;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalHitlHandlerTest {
    @Test
    void approvesWhenUserInputsY() {
        TerminalHitlHandler handler = handlerWithInput("y\n");

        ApprovalResult result = handler.requestApproval(request());

        assertEquals(ApprovalResult.Decision.APPROVED, result.decision());
    }

    @Test
    void approveAllCachesToolForCurrentSession() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TerminalHitlHandler handler = handlerWithInput("a\n", out);

        ApprovalResult first = handler.requestApproval(request());
        ApprovalResult second = handler.requestApproval(request());

        assertEquals(ApprovalResult.Decision.APPROVED_ALL, first.decision());
        assertEquals(ApprovalResult.Decision.APPROVED_ALL, second.decision());
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("自动通过"));
    }

    @Test
    void rejectsWithReason() {
        TerminalHitlHandler handler = handlerWithInput("n\n路径有误\n");

        ApprovalResult result = handler.requestApproval(request());

        assertEquals(ApprovalResult.Decision.REJECTED, result.decision());
        assertEquals("路径有误", result.reason());
    }

    @Test
    void returnsModifiedArguments() {
        TerminalHitlHandler handler = handlerWithInput("m\n{\"path\":\"safe.txt\",\"content\":\"hello\"}\n");

        ApprovalResult result = handler.requestApproval(request());

        assertEquals(ApprovalResult.Decision.MODIFIED, result.decision());
        assertEquals("{\"path\":\"safe.txt\",\"content\":\"hello\"}", result.modifiedArguments());
    }

    @Test
    void rejectsAfterRepeatedInvalidInputs() {
        TerminalHitlHandler handler = handlerWithInput("x\nx\nx\nx\nx\n");

        ApprovalResult result = handler.requestApproval(request());

        assertEquals(ApprovalResult.Decision.REJECTED, result.decision());
        assertEquals("连续多次无效输入", result.reason());
    }

    private static ApprovalRequest request() {
        return ApprovalRequest.of("write_file", "{\"path\":\"demo.txt\",\"content\":\"hello\"}", null);
    }

    private static TerminalHitlHandler handlerWithInput(String input) {
        return handlerWithInput(input, new ByteArrayOutputStream());
    }

    private static TerminalHitlHandler handlerWithInput(String input, ByteArrayOutputStream out) {
        return new TerminalHitlHandler(
            true,
            new BufferedReader(new StringReader(input)),
            new PrintStream(out, true, StandardCharsets.UTF_8)
        );
    }
}
