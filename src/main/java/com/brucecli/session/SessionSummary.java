package com.brucecli.session;

import com.brucecli.integrated.runtime.AgentMode;

import java.nio.file.Path;
import java.time.Instant;

public record SessionSummary(
    String id,
    Path file,
    String createdAt,
    Instant updatedAt,
    AgentMode mode,
    String activeLeafId,
    int messageCount
) {
}
