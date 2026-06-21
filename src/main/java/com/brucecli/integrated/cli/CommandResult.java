package com.brucecli.integrated.cli;

public record CommandResult(boolean handled, boolean exit, String output) {
    public static CommandResult notHandled() {
        return new CommandResult(false, false, "");
    }

    public static CommandResult handled(String output) {
        return new CommandResult(true, false, output);
    }

    public static CommandResult exitRequested() {
        return new CommandResult(true, true, "");
    }
}
