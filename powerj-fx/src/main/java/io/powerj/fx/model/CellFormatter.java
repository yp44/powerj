package io.powerj.fx.model;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import io.powerj.text.Formats;

/** The text of a cell, as the terminal shows it (FR-30): readable sizes, local dates, one line. */
public final class CellFormatter {

    /** A cell never shows more than this: the rest is in its tooltip. */
    static final int MAX_LENGTH = 300;
    /** Shown in place of a line break. */
    static final String LINE_BREAK = "↵";

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
            case Number n when kind == ColumnKind.SIZE -> Formats.bytes(n.longValue());
            case Instant instant -> Formats.dateTime(instant);
            case Duration d -> Formats.duration(d);
            case BigDecimal d -> d.toPlainString();
            default -> String.valueOf(value);
        };
    }
}
