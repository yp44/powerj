package io.powerj.cmdlets;

import java.util.ArrayList;
import java.util.List;

/**
 * Cuts a JSON text received in pieces into its top-level values, then parses each one (specification FR-73). The text
 * may hold several values one after the other (JSON Lines); each one is parsed as soon as it is complete, once. The
 * scan counts the brackets outside strings and keeps its state between the pieces, so a large document is read in
 * linear time. A top-level number or literal ends at the next white space.
 * <p>
 * A value that is never closed would absorb everything that follows it into the buffer: past {@code maxValueLength}
 * characters it is reported once, and the reading stops (what comes after it cannot be trusted). Several values, as in
 * JSON Lines, are each far below the limit.
 */
final class JsonSplitter {

    /** What a complete value gave. */
    sealed interface Result permits Parsed, Failed {
    }

    /** A valid value. */
    record Parsed(Object value) implements Result {
    }

    /** An invalid value: the reading goes on with the next one. */
    record Failed(JsonParser.JsonException error) implements Result {
    }

    /** Longest value read: beyond it, the input is JSON Lines, or it is not JSON. */
    static final int DEFAULT_MAX_VALUE_LENGTH = 64 * 1024 * 1024;

    private final int maxValueLength;
    private boolean abandoned;
    private final StringBuilder buffer = new StringBuilder();
    /** Next character of {@link #buffer} to scan. */
    private int scanned;
    /** Line and column of the character at {@link #scanned}. */
    private int line = 1;
    private int column = 1;
    private boolean started;

    /** Start of the value being scanned in {@link #buffer}, or -1 between two values. */
    private int valueStart = -1;
    private int valueLine;
    private int valueColumn;
    private int depth;
    private boolean scalar;
    private boolean inString;
    private boolean escape;

    JsonSplitter() {
        this(DEFAULT_MAX_VALUE_LENGTH);
    }

    /** @param maxValueLength the most characters that one value may have */
    JsonSplitter(int maxValueLength) {
        this.maxValueLength = maxValueLength;
    }

    /** Adds a piece of text, followed by a line break, and returns the values it completes. */
    List<Result> feed(CharSequence piece) {
        if (abandoned) {
            return List.of();
        }
        buffer.append(piece).append('\n');
        return scan(false);
    }

    /** End of the text: completes a last number or literal, or reports a value that is not closed. */
    List<Result> finish() {
        return abandoned ? List.of() : scan(true);
    }

    private List<Result> scan(boolean last) {
        List<Result> results = new ArrayList<>();
        for (; scanned < buffer.length(); scanned++) {
            char c = buffer.charAt(scanned);
            if (!started) {
                started = true;
                if (c == '\uFEFF') { // byte order mark
                    continue;
                }
            }
            if (valueStart >= 0 && scanned - valueStart >= maxValueLength) {
                results.add(new Failed(new JsonParser.JsonException(Messages.get("json.value.too.large",
                        maxValueLength), valueLine, valueColumn)));
                abandoned = true;
                buffer.setLength(0);
                scanned = 0;
                reset();
                valueStart = -1;
                return results;
            }
            if (valueStart >= 0 && scalar && (isSpace(c) || c == '{' || c == '[' || c == '"')) {
                complete(scanned, results);
            }
            if (valueStart < 0) {
                if (!isSpace(c)) {
                    valueStart = scanned;
                    valueLine = line;
                    valueColumn = column;
                    if (c == '{' || c == '[') {
                        depth = 1;
                    } else if (c == '"') {
                        inString = true;
                    } else {
                        scalar = true;
                    }
                }
            } else if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                    if (depth == 0) {
                        complete(scanned + 1, results);
                    }
                }
            } else if (!scalar) {
                if (c == '"') {
                    inString = true;
                } else if (c == '{' || c == '[') {
                    depth++;
                } else if ((c == '}' || c == ']') && --depth == 0) {
                    complete(scanned + 1, results);
                }
            }
            if (c == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        if (last && valueStart >= 0) {
            if (scalar) {
                complete(buffer.length(), results);
            } else {
                results.add(new Failed(new JsonParser.JsonException(Messages.get("json.end"), valueLine,
                        valueColumn)));
                reset();
            }
        }
        // keeps only the value being scanned
        int keep = valueStart < 0 ? buffer.length() : valueStart;
        buffer.delete(0, keep);
        scanned -= keep;
        if (valueStart >= 0) {
            valueStart = 0;
        }
        return results;
    }

    private void complete(int end, List<Result> results) {
        try {
            results.add(new Parsed(JsonParser.parse(buffer, valueStart, end, valueLine, valueColumn)));
        } catch (JsonParser.JsonException e) {
            results.add(new Failed(e));
        }
        reset();
    }

    private void reset() {
        valueStart = -1;
        depth = 0;
        scalar = false;
        inString = false;
        escape = false;
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }
}
