package io.powerj.cmdlets;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads CSV text (RFC 4180; specification FR-73) received in pieces: the fields of each record, as soon as the record
 * is complete. A quoted field may hold the delimiter, doubled quotes and line breaks, also across two pieces. Each
 * piece ends with a line break (the lines of {@code cat} have lost theirs). Empty lines and a byte order mark at the
 * start are skipped.
 */
final class CsvReader {

    /** A complete record, and the line where it starts (from 1). */
    record Record(List<String> fields, int line) {
    }

    private final char delimiter;
    private final List<String> fields = new ArrayList<>();
    private final StringBuilder field = new StringBuilder();
    /** The current field started with a quote. */
    private boolean quoted;
    /** Inside the quotes of the current field. */
    private boolean inQuotes;
    private boolean started;
    private int line = 1;
    private int recordLine = 1;

    CsvReader(char delimiter) {
        this.delimiter = delimiter;
    }

    /** Adds a piece of text, followed by a line break, and returns the records it completes. */
    List<Record> feed(CharSequence piece) {
        List<Record> records = new ArrayList<>();
        for (int i = 0; i < piece.length(); i++) {
            char c = piece.charAt(i);
            if (!started) {
                started = true;
                if (c == '\uFEFF') { // byte order mark
                    continue;
                }
            }
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < piece.length() && piece.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                    if (c == '\n') {
                        line++;
                    }
                }
            } else if (c == '"' && field.isEmpty() && !quoted) {
                quoted = true;
                inQuotes = true;
            } else if (c == delimiter) {
                endField();
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < piece.length() && piece.charAt(i + 1) == '\n') {
                    i++;
                }
                endRecord(records);
            } else {
                field.append(c);
            }
        }
        // the line break that ends the piece
        started = true;
        if (inQuotes) {
            field.append('\n');
            line++;
        } else {
            endRecord(records);
        }
        return records;
    }

    /** {@code true} if the text ends inside a quoted field; its line is then {@link #recordLine()}. */
    boolean unclosed() {
        return inQuotes;
    }

    /** Line where the record being read starts. */
    int recordLine() {
        return recordLine;
    }

    private void endField() {
        fields.add(field.toString());
        field.setLength(0);
        quoted = false;
    }

    private void endRecord(List<Record> records) {
        boolean empty = fields.isEmpty() && field.isEmpty() && !quoted;
        if (!empty) {
            endField();
            records.add(new Record(List.copyOf(fields), recordLine));
        }
        fields.clear();
        field.setLength(0);
        quoted = false;
        line++;
        recordLine = line;
    }
}
