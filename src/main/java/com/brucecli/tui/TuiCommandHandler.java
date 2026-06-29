package com.brucecli.tui;

@FunctionalInterface
public interface TuiCommandHandler {
    TuiCommandResult handle(String input);
}
