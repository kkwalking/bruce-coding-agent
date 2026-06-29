package com.brucecli.tui;

public record StyledSpan(String text, BruceSyntaxHighlighter.Style style) {
    public StyledSpan {
        text = text == null ? "" : text;
        style = style == null ? BruceSyntaxHighlighter.Style.NORMAL : style;
    }
}
