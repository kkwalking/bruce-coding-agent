package com.brucecli.approval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalRequestTest {
    @Test
    void formatsApprovalRequestForTerminalDisplay() {
        ApprovalRequest request = ApprovalRequest.of(
            "write_file",
            "{\"path\":\"notes/demo.txt\",\"content\":\"hello\"}",
            "确认路径是否正确"
        );

        String display = request.toDisplayText();

        assertTrue(display.contains("需要审批"));
        assertTrue(display.contains("工具: write_file"));
        assertTrue(display.contains("风险: 将写入或覆盖文件内容"));
        assertTrue(display.contains("path: \"notes/demo.txt\""));
    }

    @Test
    void calculatesDisplayWidthForCjkText() {
        assertEquals(4, ApprovalRequest.displayWidth("中文"));
        assertEquals(3, ApprovalRequest.displayWidth("a中"));
    }
}
