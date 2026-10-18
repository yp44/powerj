package io.powerj.cmdlets;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.powerj.api.Row;

/**
 * Recursive-descent parser of one JSON value (RFC 8259; specification FR-73): an object gives a {@link Row}, an array
 * an unmodifiable {@code List}, an integer a {@code Long} ({@code BigInteger} beyond), another number a
 * {@code Double}. Errors carry the line and column of the text.
 */
final class JsonParser {

    /** Deepest nesting accepted: beyond, the recursion would exhaust the stack. */
    static final int MAX_NESTING = 1000;

    private final CharSequence text;
    private final int end;
    private final int startLine;
    private final int startColumn;
    private final int start;
    private int position;
    private int nesting;

    /** Parser of {@code text[start, end)}, whose first character is at {@code line}, {@code column} (from 1). */
    private JsonParser(CharSequence text, int start, int end, int line, int column) {
        this.text = text;
        this.start = start;
        this.position = start;
        this.end = end;
        this.startLine = line;
        this.startColumn = column;
    }

    /**
     * Parses the single value of {@code text[start, end)}, surrounded by optional white space.
     *
     * @throws JsonException for invalid JSON
     */
    static Object parse(CharSequence text, int start, int end, int line, int column) {
        JsonParser parser = new JsonParser(text, start, end, line, column);
        parser.skipSpace();
        Object value = parser.value();
        parser.skipSpace();
        if (parser.position < end) {
            throw parser.error(Messages.get("json.trailing", parser.excerpt()));
        }
        return value;
    }

    /** Parses a whole text holding one value. */
    static Object parse(CharSequence text) {
        return parse(text, 0, text.length(), 1, 1);
    }

    private Object value() {
        if (position >= end) {
            throw error(Messages.get("json.end"));
        }
        char c = text.charAt(position);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield number();
                }
                throw error(Messages.get("json.unexpected", excerpt()));
            }
        };
    }

    private Row object() {
        enter();
        position++; // {
        Map<String, Object> members = new LinkedHashMap<>();
        skipSpace();
        if (peek() == '}') {
            position++;
            nesting--;
            return Row.of(members);
        }
        while (true) {
            skipSpace();
            if (peek() != '"') {
                throw error(position >= end ? Messages.get("json.end") : Messages.get("json.expected.key"));
            }
            String key = string();
            skipSpace();
            if (peek() != ':') {
                throw error(position >= end ? Messages.get("json.end") : Messages.get("json.expected.colon"));
            }
            position++;
            skipSpace();
            members.put(key, value());
            skipSpace();
            char c = peek();
            position++;
            if (c == '}') {
                nesting--;
                return Row.of(members);
            }
            if (c != ',') {
                position--;
                throw error(position >= end ? Messages.get("json.end") : Messages.get("json.expected.object"));
            }
        }
    }

    private List<Object> array() {
        enter();
        position++; // [
        List<Object> elements = new ArrayList<>();
        skipSpace();
        if (peek() == ']') {
            position++;
            nesting--;
            return Collections.unmodifiableList(elements);
        }
        while (true) {
            skipSpace();
            elements.add(value());
            skipSpace();
            char c = peek();
            position++;
            if (c == ']') {
                nesting--;
                return Collections.unmodifiableList(elements);
            }
            if (c != ',') {
                position--;
                throw error(position >= end ? Messages.get("json.end") : Messages.get("json.expected.array"));
            }
        }
    }

    private void enter() {
        if (++nesting > MAX_NESTING) {
            throw error(Messages.get("json.too.deep", MAX_NESTING));
        }
    }

    private String string() {
        int quote = position;
        position++; // "
        StringBuilder out = new StringBuilder();
        while (true) {
            if (position >= end) {
                position = quote;
                throw error(Messages.get("json.string.unclosed"));
            }
            char c = text.charAt(position);
            if (c == '"') {
                position++;
                return out.toString();
            }
            if (c == '\\') {
                position++;
                if (position >= end) {
                    continue; // reported as an unclosed string
                }
                char escaped = text.charAt(position);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (position + 4 >= end || !isHex(position + 1, position + 5)) {
                            position--;
                            throw error(Messages.get("json.escape.unicode"));
                        }
                        out.append((char) Integer.parseInt(text.subSequence(position + 1, position + 5).toString(),
                                16));
                        position += 4;
                    }
                    default -> {
                        position--;
                        throw error(Messages.get("json.escape.invalid", escaped));
                    }
                }
                position++;
                continue;
            }
            if (c < 0x20) {
                throw error(Messages.get("json.control.character"));
            }
            out.append(c);
            position++;
        }
    }

    private boolean isHex(int from, int to) {
        for (int i = from; i < to; i++) {
            if (Character.digit(text.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private Object literal(String word, Object value) {
        if (position + word.length() <= end
                && text.subSequence(position, position + word.length()).toString().equals(word)) {
            position += word.length();
            return value;
        }
        throw error(Messages.get("json.unexpected", excerpt()));
    }

    /** A number: {@code -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?}. */
    private Object number() {
        int first = position;
        if (peek() == '-') {
            position++;
        }
        if (peek() == '0') {
            position++;
            if (isDigit(peek())) {
                throw error(Messages.get("json.number.zero"));
            }
        } else if (!digits()) {
            throw error(Messages.get("json.number.invalid", excerptFrom(first)));
        }
        boolean integer = true;
        if (peek() == '.') {
            integer = false;
            position++;
            if (!digits()) {
                throw error(Messages.get("json.number.invalid", excerptFrom(first)));
            }
        }
        if (peek() == 'e' || peek() == 'E') {
            integer = false;
            position++;
            if (peek() == '+' || peek() == '-') {
                position++;
            }
            if (!digits()) {
                throw error(Messages.get("json.number.invalid", excerptFrom(first)));
            }
        }
        String number = text.subSequence(first, position).toString();
        if (!integer) {
            return Double.parseDouble(number);
        }
        try {
            return Long.parseLong(number);
        } catch (NumberFormatException e) {
            return new BigInteger(number);
        }
    }

    private boolean digits() {
        int first = position;
        while (isDigit(peek())) {
            position++;
        }
        return position > first;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** Current character, or {@code \0} at the end. */
    private char peek() {
        return position < end ? text.charAt(position) : '\0';
    }

    private void skipSpace() {
        while (position < end) {
            char c = text.charAt(position);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            position++;
        }
    }

    /** A few characters from the current position, to show in a message. */
    private String excerpt() {
        return excerptFrom(position);
    }

    private String excerptFrom(int from) {
        if (from >= end) {
            return "";
        }
        int to = from;
        while (to < end && to < from + 20 && !Character.isWhitespace(text.charAt(to))) {
            to++;
        }
        return to == from ? String.valueOf(text.charAt(from)) : text.subSequence(from, to).toString();
    }

    /** Error at the current position, with its line and column in the whole text. */
    private JsonException error(String message) {
        int line = startLine;
        int column = startColumn;
        for (int i = start; i < Math.min(position, end); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new JsonException(message, line, column);
    }

    /** Invalid JSON, at a line and a column (from 1). */
    static final class JsonException extends RuntimeException {

        private final int line;
        private final int column;

        JsonException(String message, int line, int column) {
            super(message);
            this.line = line;
            this.column = column;
        }

        int line() {
            return line;
        }

        int column() {
            return column;
        }
    }
}
