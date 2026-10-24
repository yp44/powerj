package io.powerj.fx.model;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import io.powerj.api.Language;

/** The text of a cell, as the terminal shows it (FR-30): readable sizes, local dates, one line. */
public final class CellFormatter {

    /** A cell never shows more than this: the rest is in its tooltip. */
    static final int MAX_LENGTH = 300;
    /** Shown in place of a line break. */
    static final String LINE_BREAK = "↵";

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private CellFormatter() {
    }

    /** The text of the cell: one line, at most {@value #MAX_LENGTH} characters. */
    public static String text(Object value, ColumnKind kind) {
        String full = full(value, kind);
        String line = full.replace("\r\n", LINE_BREAK).replace('\n', '↵').replace('\r', '↵');
        return line.length() > MAX_LENGTH ? line.substring(0, MAX_LENGTH - 1) + "…" : line;
    }

    /** The whole text, with its line breaks, if the cell cuts or changes it; otherwise {@code null}. */
    public static String tooltip(Object value, ColumnKind kind) {
        String full = full(value, kind);
        return full.equals(text(value, kind)) ? null : full;
    }

    /** The text of the cell as the terminal would show it, whole: what a filter looks at. */
    public static String fullText(Object value, ColumnKind kind) {
        return full(value, kind);
    }

    private static String full(Object value, ColumnKind kind) {
        return switch (value) {
            case null -> "";
            case Number n when kind == ColumnKind.SIZE -> humanBytes(n.longValue());
            case Instant instant -> DATE_TIME.format(instant.atZone(ZoneId.systemDefault()));
            case Duration d -> duration(d);
            case BigDecimal d -> d.toPlainString();
            default -> String.valueOf(value);
        };
    }

    /** {@code 14 520} → {@code 14.2 KB} (multiples of 1024, decimal separator of the current language). */
    static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Language.current(), "%.1f %s", value, units[unit]);
    }

    private static String duration(Duration d) {
        if (d.toMinutes() > 0) {
            return "%d:%02d".formatted(d.toMinutes(), d.toSecondsPart());
        }
        return String.format(Language.current(), "%.3f s", d.toNanos() / 1e9);
    }
}
