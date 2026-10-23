package io.powerj.fx.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.temporal.Temporal;
import java.util.Date;
import java.util.List;

/** The rows as text, to copy them: tab-separated for a spreadsheet, JSON for a program, or one object in full. */
public final class RowExporter {

    private RowExporter() {
    }

    /**
     * One line per row after a header, the cells separated by tabs, as they are shown but in full (a long text is not cut,
     * a line break or a tab inside a cell becomes a space so that a cell stays in its place).
     */
    public static String tsv(List<ColumnSpec> columns, List<GridRow> rows) {
        var out = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            out.append(i == 0 ? "" : "\t").append(cell(columns.get(i).name()));
        }
        for (GridRow row : rows) {
            out.append('\n');
            for (int i = 0; i < columns.size(); i++) {
                ColumnSpec column = columns.get(i);
                out.append(i == 0 ? "" : "\t").append(cell(CellFormatter.fullText(row.get(column.name()), column.kind())));
            }
        }
        return out.toString();
    }

    private static String cell(String text) {
        return text.replace('\t', ' ').replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * A JSON array with one object per row: the values themselves (numbers stay numbers, a size is its number of bytes, a
     * date is ISO 8601, a missing value is {@code null}), one row per line.
     */
    public static String json(List<ColumnSpec> columns, List<GridRow> rows) {
        var out = new StringBuilder("[");
        for (int r = 0; r < rows.size(); r++) {
            out.append(r == 0 ? "\n  {" : ",\n  {");
            for (int c = 0; c < columns.size(); c++) {
                String name = columns.get(c).name();
                out.append(c == 0 ? "" : ", ");
                string(out, name);
                out.append(": ");
                value(out, rows.get(r).get(name));
            }
            out.append('}');
        }
        return out.append(rows.isEmpty() ? "]" : "\n]").toString();
    }

    private static void value(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case Boolean b -> out.append(b);
            case BigDecimal d -> out.append(d.toPlainString());
            case BigInteger i -> out.append(i);
            case Double d when !Double.isFinite(d) -> out.append("null");
            case Float f when !Float.isFinite(f) -> out.append("null");
            case Number n -> out.append(n);
            case Instant i -> string(out, i.toString());
            case Date d -> string(out, d.toInstant().toString());
            case Temporal t -> string(out, t.toString());
            default -> string(out, String.valueOf(value));
        }
    }

    private static void string(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u%04x".formatted((int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** One object in full, a property per line: {@code name : value}, the names aligned, long texts and line breaks kept. */
    public static String details(List<ColumnSpec> columns, GridRow row) {
        int width = columns.stream().mapToInt(c -> c.name().length()).max().orElse(0);
        var out = new StringBuilder();
        for (ColumnSpec column : columns) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(column.name()).append(" ".repeat(width - column.name().length())).append(" : ")
                    .append(CellFormatter.fullText(row.get(column.name()), column.kind()));
        }
        return out.toString();
    }
}
