package io.powerj.core.format;

import java.time.Duration;

import io.powerj.text.Formats;

/** Rendering of a value on a single line: command argument, interpolation, table cell. */
public final class Values {

    private Values() {
    }

    public static String text(Object value) {
        return switch (value) {
            case null -> "";
            case Duration d -> Formats.duration(d);
            default -> String.valueOf(value);
        };
    }

    /** {@code null}, or type and abbreviated value: {@code String "abc"}. */
    public static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        String text = Values.text(value);
        if (text.length() > 30) {
            text = text.substring(0, 27) + "…";
        }
        return value.getClass().getSimpleName() + (value instanceof CharSequence ? " \"" + text + "\"" : " " + text);
    }
}
