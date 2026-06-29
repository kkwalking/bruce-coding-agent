package com.brucecli.tui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BruceSyntaxHighlighterTest {
    @Test
    void highlightsCoreInputPatterns() {
        String input = "/web search @image:<shot.png> @clipboard rm -rf / api_key";

        var spans = BruceSyntaxHighlighter.highlight(input);

        assertTrue(spans.stream().anyMatch(span -> span.style() == BruceSyntaxHighlighter.Style.COMMAND));
        assertTrue(spans.stream().anyMatch(span -> span.style() == BruceSyntaxHighlighter.Style.IMAGE));
        assertTrue(spans.stream().anyMatch(span -> span.style() == BruceSyntaxHighlighter.Style.DANGER));
        assertTrue(spans.stream().anyMatch(span -> span.style() == BruceSyntaxHighlighter.Style.SECRET));
    }
}
