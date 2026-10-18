package io.powerj.cmdlets;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Writes CSV lines (RFC 4180; specification FR-73): a field is quoted when it holds the delimiter, a quote or a line
 * break, or starts or ends with a space; numbers use {@code .} without exponent, nested values are compact JSON.
 */
final class CsvWriter {

    private final char delimiter;

    CsvWriter(char delimiter) {
        this.delimiter = delimiter;
    }

    /** One line of fields, without line break. */
    String line(List<?> values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.append(delimiter);
            }
            quote(text(values.get(i)), out);
        }
        return out.toString();
    }

    /** Text of a field, before quoting. */
    static String text(Object value) {
        return switch (value) {
            case null -> "";
            case CharSequence text -> text.toString();
            case Double d when Double.isFinite(d) -> BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
            case Float f when Float.isFinite(f) -> new BigDecimal(f.toString()).stripTrailingZeros().toPlainString();
            case BigDecimal d -> d.toPlainString();
            case Enum<?> e -> e.name();
            case Optional<?> optional -> text(optional.orElse(null));
            default -> {
                if (JsonWriter.isContainer(value)) {
                    StringBuilder json = new StringBuilder();
                    new JsonWriter(false, 10).write(value, json, 0);
                    yield json.toString();
                }
                yield String.valueOf(value);
            }
        };
    }

    private void quote(String text, StringBuilder out) {
        boolean needed = !text.isEmpty() && (text.charAt(0) == ' ' || text.charAt(text.length() - 1) == ' ');
        for (int i = 0; i < text.length() && !needed; i++) {
            char c = text.charAt(i);
            needed = c == delimiter || c == '"' || c == '\n' || c == '\r';
        }
        if (!needed) {
            out.append(text);
            return;
        }
        out.append('"').append(text.replace("\"", "\"\"")).append('"');
    }

    /**
     * Delimiter given to {@code --delimiter}: one character, or {@code tab}; {@code ,} when {@code null}.
     *
     * @throws IllegalArgumentException for anything else, or a quote or a line break
     */
    static char delimiter(String option) {
        if (option == null) {
            return ',';
        }
        if (option.equalsIgnoreCase("tab") || option.equals("\\t")) {
            return '\t';
        }
        if (option.length() != 1 || option.charAt(0) == '"' || option.charAt(0) == '\n' || option.charAt(0) == '\r') {
            throw new IllegalArgumentException(Messages.get("csv.delimiter.invalid", option));
        }
        return option.charAt(0);
    }
}
