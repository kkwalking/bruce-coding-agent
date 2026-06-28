package com.brucecli.agent;

import com.brucecli.llm.Message;

@FunctionalInterface
public interface AgentTranscriptListener {
    void onDurableMessage(Message message);

    static AgentTranscriptListener noop() {
        return message -> {
        };
    }
}
