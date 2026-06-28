package com.brucecli.render;

import com.brucecli.rag.model.IndexProgress;

import java.io.PrintStream;

public interface BruceRenderer extends AutoCloseable {
    void start();

    void renderWelcome(BruceStatusInfo status);

    void beforeInput(BruceStatusInfo status);

    void afterInput(BruceStatusInfo status);

    void appendUserMessage(String message);

    void appendAssistantMessage(String message);

    void appendSystemMessage(String message);

    void appendActivity(String message);

    default void beginStreamingAssistantMessage() {
    }

    default void appendStreamingAssistantDelta(String delta) {
    }

    default void finishStreamingAssistantMessage(String finalText) {
        appendAssistantMessage(finalText);
    }

    void updateStatus(BruceStatusInfo status);

    default void updateIndexProgress(IndexProgress progress) {
    }

    String inputPrompt();

    default String inputRightPrompt() {
        return null;
    }

    PrintStream stream();

    @Override
    void close();
}
