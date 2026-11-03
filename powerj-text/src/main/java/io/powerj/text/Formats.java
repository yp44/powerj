package io.powerj.text;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import io.powerj.api.Language;

/**
 * The text of a size, a duration and a date, the same in the terminal, the grid and the exports (specification FR-30).
 * <p>
 * A size follows the decimal separator of the current {@link Language} ({@code 14.2 KB}, {@code 14,2 KB}): it is only
 * shown. A duration always has a decimal point ({@code 1.500 s}), because its text is also what a script interpolates or
 * passes as an argument, and that must not depend on the language of the machine.
 */
public final class Formats {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final String[] SIZE_UNITS = {"KB", "MB", "GB", "TB", "PB"};

    private Formats() {
    }

    /** {@code 14 520} → {@code 14.2 KB} (multiples of 1024, decimal separator of the current language). */
    public static String bytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < SIZE_UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Language.current(), "%.1f %s", value, SIZE_UNITS[unit]);
    }

    /**
     * {@code 1 min 30 s}, {@code 1.500 s}, {@code 2.0 µs}, {@code 150 ns}: minutes and seconds from a minute on, seconds
     * to the millisecond from a millisecond on, then microseconds (a block of a line lasts a few, see the perf mode) and
     * nanoseconds. The words come from the current language, the decimal separator never does.
     */
    public static String duration(Duration duration) {
        if (duration.toMinutes() > 0) {
            return Messages.get("duration.minutes", duration.toMinutes(), "%02d".formatted(duration.toSecondsPart()));
        }
        if (duration.compareTo(Duration.ofMillis(1)) < 0) {
            long nanos = duration.toNanos();
            return nanos < 1000 ? Messages.get("duration.nanos", nanos)
                    : Messages.get("duration.micros", String.format(Locale.ROOT, "%.1f", nanos / 1e3));
        }
        return Messages.get("duration.seconds", String.format(Locale.ROOT, "%.3f", duration.toNanos() / 1e9));
    }

    /** {@code 2026-10-05 18:12}: the local date and time of an instant, to the minute. */
    public static String dateTime(Instant instant) {
        return DATE_TIME.format(instant.atZone(ZoneId.systemDefault()));
    }
}
