package com.brucecli.tui;

public record TuiCommandResult(boolean handled, boolean exit, String output) {
    public TuiCommandResult {
        output = output == null ? "" : output;
    }
}
