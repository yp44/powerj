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
     * The two lines to show under the message: the line and the caret.
     *
     * @param line   what the user typed
     * @param offset index in {@code line} (UTF-16 units), clamped to the line
     */
    public static List<String> show(String line, int offset) {
        int at = Math.max(0, Math.min(offset, line.length()));
        return List.of(line, " ".repeat(DisplayWidth.of(line.substring(0, at))) + "^");
    }
}
