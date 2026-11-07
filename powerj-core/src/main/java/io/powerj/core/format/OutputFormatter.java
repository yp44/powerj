package io.powerj.core.format;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

import io.powerj.api.Bytes;
import io.powerj.api.Display;
import io.powerj.api.Highlight;
import io.powerj.api.Language;
import io.powerj.api.Span;
import io.powerj.core.i18n.Messages;
import io.powerj.text.Formats;
import io.powerj.text.RecordView;

/**
 * Display of values (specification FR-30):
 * <ul>
 *   <li>successive records of the same type: a table (columns from {@link Display}, or all components
 *       if there are at most {@value #MAX_TABLE_COLUMNS}); otherwise a {@code name : value} list;</li>
 *   <li>successive {@link io.powerj.api.Row}s with the same columns ({@code select}): a table, whatever the
 *       number of columns;</li>
 *   <li>{@code Map}: key / value table; collections and arrays: one element per line;</li>
 *   <li>scalars: one readable line ({@link Bytes} sizes, local dates, durations).</li>
 * </ul>
 * On a terminal that accepts colors, the parts of a {@link Highlight} component are shown in bold red
 * (matches of {@code grep}, FR-64).
 * Tables are displayed as they stream: the column widths are computed from the first
 * {@value #WIDTH_SAMPLE} lines, and subsequent lines are truncated to these widths.
 */
public final class OutputFormatter implements AutoCloseable {

    static final int MAX_TABLE_COLUMNS = 5;
    private static final int WIDTH_SAMPLE = 50;
    private static final int MIN_COLUMN = 4;
    private static final String GAP = "  ";

    private static final String HIGHLIGHT_ON = "\u001b[1;31m";
    private static final String HIGHLIGHT_OFF = "\u001b[0m";

    /**
     * Column of a table.
     *
     * @param reader    reads the value of the column in a record or a {@link io.powerj.api.Row}
     * @param type      declared type of the values (right alignment of numbers)
     * @param highlight name of the {@code List<Span>} component of a {@link Highlight} column, or {@code null}
     */
    private record Column(String name, Function<Object, Object> reader, Class<?> type, boolean bytes,
                          String highlight) { }

    /** Row of a table: the text of the cells and, for each one, the parts to highlight ({@code null}: none). */
    private record Row(String[] cells, List<List<Span>> highlights) {
        Row(String[] cells) {
            this(cells, Collections.nCopies(cells.length, null));
        }
    }

    private final PrintWriter out;
    private final int width;
    private final boolean colors;

    /** Type of the current table: the record class, or the column names of {@link io.powerj.api.Row}s. */
    private Object tableType;
    private List<Column> columns;
    private final List<Row> sample = new ArrayList<>();
    private int[] widths;
    private boolean[] rightAligned;

    /**
     * How the value of a component of a record is read: the engine's one way to read a property
     * ({@code PropertyAccess.read}), given by the caller. The formatter does not reflect on a record itself (#130): reading
     * is allowed to the engine alone, and a {@code Method} made accessible here would be accessible for every module that
     * holds it. It is not a dependency of this package on the one that reads, which depends on this one.
     */
    @FunctionalInterface
    public interface ComponentReader {

        /** The value of the component called {@code name} (the case matters) of {@code record}. */
        Object read(Record record, String name);
    }

    private final ComponentReader reader;

    /**
     * @param width  terminal width in characters (0 or less: 120)
     * @param reader reads the components of the records shown
     */
    public OutputFormatter(PrintWriter out, int width, ComponentReader reader) {
        this(out, width, false, reader);
    }

    /**
     * @param width  terminal width in characters (0 or less: 120)
     * @param colors {@code true} if the output accepts ANSI colors
     * @param reader reads the components of the records shown
     */
    public OutputFormatter(PrintWriter out, int width, boolean colors, ComponentReader reader) {
        this.out = out;
        this.width = width > 0 ? width : 120;
        this.colors = colors;
        this.reader = reader;
    }

    public void accept(Object value) {
        if (value instanceof Path path) {
            line(path.toString()); // a Path is also an Iterable<Path>
            return;
        }
        if (ValueUnroller.unroll(value, ValueUnroller.Mode.DISPLAY, this::accept)) {
            return; // the elements were displayed
        }
        switch (value) {
            case null -> { }
            case Record record -> record(record);
            case io.powerj.api.Row row -> row(row);
            case Map<?, ?> map -> map(map);
            case String text -> text(text);
            default -> line(cell(value, false));
        }
    }

    /** Ends the current table. */
    @Override
    public void close() {
        endTable();
        out.flush();
    }

    private void line(String text) {
        endTable();
        out.println(text);
    }

    /** Text as is: no line break added when it already ends with one (content read by cat). */
    private void text(String text) {
        if (text.endsWith("\n")) {
            endTable();
            out.print(text);
        } else {
            line(text);
        }
    }

    private void record(Record record) {
        Class<?> type = record.getClass();
        List<Column> tableColumns = tableColumns(type);
        if (tableColumns == null) {
            endTable();
            listRecord(record);
            return;
        }
        tableRow(type, tableColumns, record);
    }

    /** Rows of {@code select}: a table per list of columns, whatever their number (FR-69). */
    private void row(io.powerj.api.Row row) {
        List<String> names = List.copyOf(row.keySet());
        if (names.isEmpty()) {
            return;
        }
        if (!names.equals(tableType)) {
            List<Column> rowColumns = new ArrayList<>(names.size());
            for (String name : names) {
                Object first = row.get(name);
                rowColumns.add(new Column(name, value -> ((Map<?, ?>) value).get(name),
                        first == null ? Object.class : first.getClass(), row.sizeColumns().contains(name), null));
            }
            tableRow(names, rowColumns, row);
        } else {
            tableRow(names, columns, row);
        }
    }

    /** Adds a line to the table of type {@code type}, starting a new table if the type changes. */
    private void tableRow(Object type, List<Column> tableColumns, Object value) {
        if (!type.equals(tableType)) {
            endTable();
            tableType = type;
            columns = tableColumns;
        }
        String[] cells = new String[columns.size()];
        List<List<Span>> highlights = new ArrayList<>(cells.length);
        for (int i = 0; i < cells.length; i++) {
            Column column = columns.get(i);
            Object cell = column.reader().apply(value);
            cells[i] = cell(cell, column.bytes());
            highlights.add(value instanceof Record record ? highlights(cell, column.highlight(), record) : null);
        }
        Row row = new Row(cells, highlights);
        if (widths == null) {
            sample.add(row);
            if (sample.size() >= WIDTH_SAMPLE) {
                printHeaderAndSample();
            }
        } else {
            printRow(row);
        }
    }

    private void endTable() {
        if (tableType != null) {
            if (widths == null) {
                printHeaderAndSample();
            }
            tableType = null;
            columns = null;
            widths = null;
            sample.clear();
        }
    }

    private void printHeaderAndSample() {
        int n = columns.size();
        widths = new int[n];
        rightAligned = new boolean[n];
        for (int i = 0; i < n; i++) {
            widths[i] = columns.get(i).name().length();
            Class<?> t = columns.get(i).type();
            rightAligned[i] = columns.get(i).bytes() || Number.class.isAssignableFrom(t)
                    || (t.isPrimitive() && t != boolean.class && t != char.class);
        }
        for (Row row : sample) {
            for (int i = 0; i < n; i++) {
                widths[i] = Math.max(widths[i], row.cells()[i].length());
            }
        }
        fitToWidth();
        String[] header = new String[n];
        String[] rule = new String[n];
        for (int i = 0; i < n; i++) {
            header[i] = columns.get(i).name();
            rule[i] = "-".repeat(Math.min(header[i].length(), widths[i]));
        }
        printRow(new Row(header));
        printRow(new Row(rule));
        sample.forEach(this::printRow);
        sample.clear();
    }

    /** Shrinks the widest columns as long as the table exceeds the terminal width. */
    private void fitToWidth() {
        int total = Arrays.stream(widths).sum() + GAP.length() * (widths.length - 1);
        while (total > width) {
            int widest = 0;
            for (int i = 1; i < widths.length; i++) {
                if (widths[i] > widths[widest]) {
                    widest = i;
                }
            }
            if (widths[widest] <= MIN_COLUMN) {
                return;
            }
            widths[widest]--;
            total--;
        }
    }

    private void printRow(Row row) {
        String[] cells = row.cells();
        var line = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            String text = truncate(cells[i], widths[i]);
            String padding = " ".repeat(widths[i] - text.length());
            // the ellipsis of a truncated cell is never highlighted
            String shown = highlight(text, row.highlights().get(i),
                    text.length() < cells[i].length() ? text.length() - 1 : text.length());
            boolean last = i == cells.length - 1;
            if (rightAligned[i]) {
                line.append(padding).append(shown);
            } else {
                line.append(shown);
                if (!last) {
                    line.append(padding);
                }
            }
            if (!last) {
                line.append(GAP);
            }
        }
        out.println(line.toString().stripTrailing());
    }

    private static String truncate(String text, int width) {
        return text.length() <= width ? text : text.substring(0, Math.max(0, width - 1)) + "…";
    }

    private void listRecord(Record record) {
        List<RecordView.Component> components = RecordView.of(record.getClass()).components();
        int nameWidth = components.stream().mapToInt(c -> c.name().length()).max().orElse(0);
        for (RecordView.Component c : components) {
            Column column = column(c);
            Object value = column.reader().apply(record);
            String text = cell(value, c.bytes());
            text = highlight(text, highlights(value, column.highlight(), record), text.length());
            out.println(("%-" + nameWidth + "s : %s").formatted(c.name(), text));
        }
        out.println();
    }

    private void map(Map<?, ?> map) {
        endTable();
        String key = Messages.get("table.key");
        String value = Messages.get("table.value");
        int keyWidth = Math.max(key.length(),
                map.keySet().stream().mapToInt(k -> Values.text(k).length()).max().orElse(0));
        out.println(("%-" + keyWidth + "s  %s").formatted(key, value));
        out.println(("%-" + keyWidth + "s  %s").formatted("-".repeat(key.length()), "-".repeat(value.length())));
        map.forEach((k, v) -> out.println(("%-" + keyWidth + "s  %s").formatted(Values.text(k), cell(v, false))));
    }

    /** Table columns for this type, or {@code null} if it is displayed as a list. */
    private List<Column> tableColumns(Class<?> type) {
        RecordView view = RecordView.of(type);
        List<RecordView.Component> chosen = view.displayed();
        if (view.declaresDisplay()) {
            return chosen.isEmpty() ? null : chosen.stream().map(this::column).toList();
        }
        List<RecordView.Component> all = view.components();
        if (all.isEmpty() || all.size() > MAX_TABLE_COLUMNS) {
            return null;
        }
        return all.stream().map(this::column).toList();
    }

    /**
     * The column of a component. The structure comes from {@link RecordView}; the value is read by the
     * {@link ComponentReader}, as every command reads a property (#130).
     */
    private Column column(RecordView.Component component) {
        String name = component.name();
        String highlight = component.highlight() == null ? null : component.highlight().getName();
        return new Column(name, value -> read((Record) value, name), component.recordComponent().getType(),
                component.bytes(), highlight);
    }

    /** Parts to highlight in the text of {@code value}, or {@code null} if none (or no colors). */
    private List<Span> highlights(Object value, String highlight, Record record) {
        if (!colors || highlight == null || !(value instanceof CharSequence)
                || !(read(record, highlight) instanceof List<?> spans) || spans.isEmpty()) {
            return null;
        }
        return spans.stream().filter(Span.class::isInstance).map(Span.class::cast).toList();
    }

    /** Text with its parts in bold red, limited to the first {@code limit} characters. */
    static String highlight(String text, List<Span> spans, int limit) {
        if (spans == null || spans.isEmpty()) {
            return text;
        }
        var result = new StringBuilder();
        int position = 0;
        for (Span span : spans.stream().sorted(Comparator.comparingInt(Span::start)).toList()) {
            int start = Math.max(span.start(), position);
            int end = Math.min(span.end(), limit);
            if (start >= end) {
                continue;
            }
            result.append(text, position, start).append(HIGHLIGHT_ON).append(text, start, end).append(HIGHLIGHT_OFF);
            position = end;
        }
        return result.append(text, position, text.length()).toString();
    }

    /** The value of a component, or {@code ?} if it cannot be read (an interruption is not an unreadable value). */
    private Object read(Record record, String name) {
        try {
            return reader.read(record, name);
        } catch (CancellationException e) {
            throw e;
        } catch (RuntimeException e) {
            return "?";
        }
    }

    /** Value in readable format; its line breaks are kept (a text read by cat is shown as is). */
    static String cell(Object value, boolean bytes) {
        return switch (value) {
            case null -> "";
            case Number n when bytes -> humanBytes(n.longValue());
            case Instant instant -> Formats.dateTime(instant);
            case Duration d -> Values.text(d);
            case String s -> s;
            default -> Values.text(value);
        };
    }

    /** {@code 14 520} → {@code 14.2 KB} (multiples of 1024, decimal separator of the current {@link Language}). */
    public static String humanBytes(long bytes) {
        return Formats.bytes(bytes);
    }
}
