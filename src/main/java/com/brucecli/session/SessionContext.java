package com.brucecli.session;

import com.brucecli.integrated.runtime.AgentMode;
import com.brucecli.llm.Message;

import java.nio.file.Path;
import java.util.List;

public record SessionContext(
    String sessionId,
    Path file,
    String activeLeafId,
    AgentMode mode,
    int messageCount,
    List<Message> messages
) {
}
