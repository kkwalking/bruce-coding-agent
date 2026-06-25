package com.brucecli.render;

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

    void updateStatus(BruceStatusInfo status);

    String inputPrompt();

    default String inputRightPrompt() {
        return null;
    }

    PrintStream stream();

    @Override
    void close();
}
