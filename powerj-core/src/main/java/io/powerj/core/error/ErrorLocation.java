package io.powerj.core.error;

import java.util.List;

import io.powerj.text.DisplayWidth;

/**
 * The line that a syntax error is about, with a {@code ^} under the place (audit v2, U-03): the message says what is
 * wrong, this shows where. The caret is under the right column even after wide characters or emoji.
 */
public final class ErrorLocation {

    private ErrorLocation() {
    }

    /**
     * The two lines to show under the message: the line of the input that holds the error (an input can span several,
     * a text block or a continued line) and the caret. A tab before the error is kept in the padding, so that the caret
     * falls under the right column whatever the width of the tab.
     *
     * @param input  what the user typed
     * @param offset index in {@code input} (UTF-16 units), clamped to it
     */
    public static List<String> show(String input, int offset) {
        int at = Math.max(0, Math.min(offset, input.length()));
        int start = input.lastIndexOf('\n', at - 1) + 1;
        int end = input.indexOf('\n', at);
        String line = input.substring(start, end < 0 ? input.length() : end).replace("\r", "");
        String before = input.substring(start, at).replace("\r", "");
        var padding = new StringBuilder();
        for (int i = 0; i < before.length(); ) {
            int codePoint = before.codePointAt(i);
            padding.append(codePoint == '\t' ? "\t" : " ".repeat(DisplayWidth.of(codePoint)));
            i += Character.charCount(codePoint);
        }
        return List.of(line, padding + "^");
    }
}
