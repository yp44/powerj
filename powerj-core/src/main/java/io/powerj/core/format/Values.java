package io.powerj.core.format;

import java.time.Duration;
import java.util.Locale;

import io.powerj.core.i18n.Messages;

/** Rendering of a value on a single line: command argument, interpolation, table cell. */
public final class Values {

    private Values() {
    }

    public static String text(Object value) {
        return switch (value) {
            case null -> "";
            case Duration d -> duration(d);
            default -> String.valueOf(value);
        };
    }

    private static String duration(Duration d) {
        if (d.toMinutes() > 0) {
            return Messages.get("duration.minutes", d.toMinutes(), "%02d".formatted(d.toSecondsPart()));
        }
        if (d.compareTo(Duration.ofMillis(1)) < 0) { // perf (FR-76): a block lasts a few microseconds
            long nanos = d.toNanos();
            return nanos < 1000 ? Messages.get("duration.nanos", nanos)
                    : Messages.get("duration.micros", String.format(Locale.ROOT, "%.1f", nanos / 1e3));
        }
        return Messages.get("duration.seconds", String.format(Locale.ROOT, "%.3f", d.toNanos() / 1e9));
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
