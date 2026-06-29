package com.brucecli.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BruceSyntaxHighlighter {
    private static final Pattern SLASH_COMMAND = Pattern.compile("^/\\S*");
    private static final Pattern IMAGE_REFERENCE = Pattern.compile("@image:(<[^>]*>|\\S*)|@clipboard(?![\\p{L}\\p{N}_])");
    private static final Pattern AT_REFERENCE = Pattern.compile("@[\\p{L}\\p{N}_./:~${}<>-]+");
    private static final Pattern DANGEROUS = Pattern.compile(
        "(?i)\\b(sudo|mkfs|shutdown|reboot|halt|poweroff)\\b"
            + "|\\brm\\s+-[a-z]*r[a-z]*f[a-z]*\\s+(/|~|\\$home)"
            + "|\\bcurl\\b[^|\\n]*\\|\\s*(sh|bash|zsh|fish|ksh)\\b"
            + "|\\bwget\\b[^|\\n]*\\|\\s*(sh|bash|zsh|fish|ksh)\\b"
            + "|\\bdd\\b[^\\n]*\\bof=/dev/"
    );
    private static final Pattern SECRET_HINT = Pattern.compile(
        "(?i)\\b(api[_-]?key|token|password|secret|authorization|bearer)\\b"
    );

    private BruceSyntaxHighlighter() {
    }

    public static List<StyledSpan> highlight(String input) {
        String text = input == null ? "" : input;
        if (text.isEmpty()) {
            return List.of();
        }
        Style[] styles = new Style[text.length()];
        apply(styles, text, AT_REFERENCE, Style.MENTION);
        apply(styles, text, IMAGE_REFERENCE, Style.IMAGE);
        apply(styles, text, SECRET_HINT, Style.SECRET);
        apply(styles, text, DANGEROUS, Style.DANGER);
        apply(styles, text, SLASH_COMMAND, Style.COMMAND);

        List<StyledSpan> spans = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Style currentStyle = styleAt(styles, 0);
        for (int i = 0; i < text.length(); i++) {
            Style next = styleAt(styles, i);
            if (next != currentStyle && current.length() > 0) {
                spans.add(new StyledSpan(current.toString(), currentStyle));
                current.setLength(0);
                currentStyle = next;
            }
            current.append(text.charAt(i));
        }
        if (current.length() > 0) {
            spans.add(new StyledSpan(current.toString(), currentStyle));
        }
        return spans;
    }

    private static void apply(Style[] styles, String text, Pattern pattern, Style style) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            int start = Math.max(0, matcher.start());
            int end = Math.min(styles.length, matcher.end());
            for (int i = start; i < end; i++) {
                styles[i] = style;
            }
        }
    }

    private static Style styleAt(Style[] styles, int index) {
        Style style = styles[index];
        return style == null ? Style.NORMAL : style;
    }

    public enum Style {
        NORMAL,
        COMMAND,
        MENTION,
        IMAGE,
        DANGER,
        SECRET
    }
}
