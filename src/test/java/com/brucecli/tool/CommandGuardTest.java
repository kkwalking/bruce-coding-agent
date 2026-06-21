package com.brucecli.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandGuardTest {
    private final CommandGuard guard = new CommandGuard();

    @Test
    void rejectsDangerousCommands() {
        assertFalse(guard.check("sudo rm -rf /").allowed());
        assertFalse(guard.check("mkfs.ext4 /dev/disk1").allowed());
    }

    @Test
    void rejectsFullDiskScansButAllowsWorkspaceScans() {
        assertFalse(guard.check("find / -name '*.java'").allowed());
        assertFalse(guard.check("grep -R foo /").allowed());
        assertTrue(guard.check("find . -name '*.java'").allowed());
    }
}
