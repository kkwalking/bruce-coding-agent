package com.brucecli.integrated.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BruceTuiAppTest {
    @TempDir
    Path tempDir;

    @Test
    void storesInputHistoryUnderBruceCliDirectory() {
        assertEquals(
            tempDir.resolve(".brucecli/history").toAbsolutePath().normalize(),
            BruceTuiApp.resolveHistoryFile(tempDir)
        );
    }
}
