package com.brucecli.tui;

public record CompletionItem(
    String value,
    String display,
    String description,
    String group,
    boolean complete
) {
    public CompletionItem {
        value = value == null ? "" : value;
        display = display == null || display.isBlank() ? value : display;
        description = description == null ? "" : description;
        group = group == null ? "" : group;
    }
}
