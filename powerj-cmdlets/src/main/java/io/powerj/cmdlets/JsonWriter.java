package io.powerj.cmdlets;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.stream.BaseStream;

/**
 * Writes Java objects as JSON text (specification FR-73): records and maps as objects, collections, arrays and
 * streams as arrays, numbers and booleans as is, anything else as the string of its {@code toString()}. Getters are
 * never called. Containers nested deeper than {@code maxDepth} are written as their text.
 */
final class JsonWriter {

    private static final String INDENT = "  ";

    private final boolean pretty;
    private final int maxDepth;
    private boolean truncated;
    private int written;

    /**
     * @param pretty   indents by two spaces, one member or element per line
     * @param maxDepth containers at this nesting level or deeper are written as their text; the object written is at
     *                 level 0
     */
    JsonWriter(boolean pretty, int maxDepth) {
        this.pretty = pretty;
        this.maxDepth = maxDepth;
    }

    /** {@code true} once a value deeper than the maximum depth has been written as its text. */
    boolean truncated() {
        return truncated;
    }

    /**
     * Writes {@code value}, its nested lines indented by {@code indent} levels.
     *
     * @throws Properties.Unreadable if a record component cannot be read
     */
    void write(Object value, StringBuilder out, int indent) {
        value(value, out, 0, indent);
    }

    private void value(Object value, StringBuilder out, int depth, int indent) {
        if (++written % 1024 == 0 && Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
        switch (value) {
            case null -> out.append("null");
            case Boolean b -> out.append(b);
            case Double d -> number(d, out);
            case Float f -> number(f.doubleValue(), out);
            case Integer _, Long _, Short _, Byte _, BigInteger _ -> out.append(value);
            case BigDecimal d -> out.append(d);
            case CharSequence text -> string(text, out);
            case Character c -> string(c.toString(), out);
            case Enum<?> e -> string(e.name(), out);
            case Optional<?> optional -> value(optional.orElse(null), out, depth, indent);
            default -> {
                if (!isContainer(value)) {
                    string(String.valueOf(value), out);
                } else if (depth >= maxDepth) {
                    truncated = true;
                    string(String.valueOf(value), out);
                } else if (value instanceof Map<?, ?> map) {
                    object(map, out, depth, indent);
                } else if (value instanceof Record record) {
                    object(Properties.of(record), out, depth, indent);
                } else {
                    array(elements(value), out, depth, indent);
                }
            }
        }
    }

    /** A map, record, collection, array or stream; a {@code Path}, though {@code Iterable}, is a scalar. */
    static boolean isContainer(Object value) {
        return value instanceof Map<?, ?> || value instanceof Record
                || (value instanceof Iterable<?> && !(value instanceof Path))
                || value instanceof Iterator<?> || value instanceof BaseStream<?, ?> || value.getClass().isArray();
    }

    private static Iterator<?> elements(Object value) {
        return switch (value) {
            case Iterable<?> iterable -> iterable.iterator();
            case Iterator<?> iterator -> iterator;
            case BaseStream<?, ?> stream -> stream.iterator();
            default -> new Iterator<>() { // an array, also of primitives
                private int next;

                @Override
                public boolean hasNext() {
                    return next < Array.getLength(value);
                }

                @Override
                public Object next() {
                    return Array.get(value, next++);
                }
            };
        };
    }

    private void object(Map<?, ?> map, StringBuilder out, int depth, int indent) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            newLine(out, indent + 1);
            string(String.valueOf(entry.getKey()), out);
            out.append(pretty ? ": " : ":");
            value(entry.getValue(), out, depth + 1, indent + 1);
        }
        newLine(out, indent);
        out.append('}');
    }

    private void array(Iterator<?> elements, StringBuilder out, int depth, int indent) {
        if (!elements.hasNext()) {
            out.append("[]");
            return;
        }
        out.append('[');
        boolean first = true;
        while (elements.hasNext()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            newLine(out, indent + 1);
            value(elements.next(), out, depth + 1, indent + 1);
        }
        newLine(out, indent);
        out.append(']');
    }

    /** Line break and indentation, when indenting. */
    void newLine(StringBuilder out, int indent) {
        if (pretty) {
            out.append('\n');
            out.append(INDENT.repeat(indent));
        }
    }

    /** A number; {@code NaN} and infinities, which JSON has not, give {@code null}. */
    private static void number(double d, StringBuilder out) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            out.append("null");
        } else {
            out.append(d);
        }
    }

    /** A JSON string (RFC 8259): quotes, backslashes and control characters escaped, the rest as is. */
    static void string(CharSequence text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
