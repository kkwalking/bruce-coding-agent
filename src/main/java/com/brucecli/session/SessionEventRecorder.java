package com.brucecli.session;

import com.brucecli.event.BruceEvent;
import com.brucecli.event.BruceEventListener;
import com.brucecli.event.BruceEvents;

import java.io.IOException;
import java.util.function.Consumer;

public class SessionEventRecorder implements BruceEventListener {
    private final SessionManager sessionManager;
    private final Consumer<String> errorHandler;

    public SessionEventRecorder(SessionManager sessionManager, Consumer<String> errorHandler) {
        this.sessionManager = sessionManager;
        this.errorHandler = errorHandler == null ? ignored -> {
        } : errorHandler;
    }

    @Override
    public void onEvent(BruceEvent event) {
        try {
            if (event instanceof BruceEvents.MessageCompleted completed) {
                if (completed.durable()) {
                    sessionManager.appendMessage(completed.message());
                }
            } else if (event instanceof BruceEvents.ModeChanged changed) {
                sessionManager.appendModeChange(changed.mode());
            }
        } catch (IOException exception) {
            errorHandler.accept("Session 写入失败: " + exception.getMessage());
        }
    }
}
