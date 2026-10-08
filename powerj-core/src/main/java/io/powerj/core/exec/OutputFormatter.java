package io.powerj.core.exec;

import java.io.PrintWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import io.powerj.api.Bytes;
import io.powerj.api.Display;

/**
 * Display of values (specification FR-30):
 * <ul>
 *   <li>successive records of the same type: a table (columns from {@link Display}, or all components
 *       if there are at most {@value #MAX_TABLE_COLUMNS}); otherwise a {@code name : value} list;</li>
 *   <li>{@code Map}: key / value table; collections and arrays: one element per line;</li>
 *   <li>scalars: one readable line ({@link Bytes} sizes, local dates, durations).</li>
 * </ul>
 * Tables are displayed as they stream: the column widths are computed from the first
 * {@value #WIDTH_SAMPLE} lines, and subsequent lines are truncated to these widths.
 */
public final class OutputFormatter implements AutoCloseable {

    static final int MAX_TABLE_COLUMNS = 5;
    private static final int WIDTH_SAMPLE = 50;
    private static final int MIN_COLUMN = 4;
    private static final String GAP = "  ";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private record Column(String name, Method accessor, boolean bytes) { }

    private final PrintWriter out;
    private final int width;

    private Class<?> tableType;
    private List<Column> columns;
    private final List<String[]> sample = new ArrayList<>();
    private int[] widths;
    private boolean[] rightAligned;

    /** @param width terminal width in characters (0 or less: 120) */
    public OutputFormatter(PrintWriter out, int width) {
        this.out = out;
        this.width = width > 0 ? width : 120;
    }

    public void accept(Object value) {
        switch (value) {
            case null -> { }
            case Path path -> line(path.toString()); // a Path is also an Iterable<Path>
            case Iterable<?> items -> items.forEach(this::accept);
            case java.util.stream.BaseStream<?, ?> stream -> { // Stream, IntStream…: elements displayed
                try (stream) {
                    stream.iterator().forEachRemaining(this::accept);
                }
            }
            case java.util.Iterator<?> iterator -> iterator.forEachRemaining(this::accept);
            case Object array when array.getClass().isArray() -> {
                for (int i = 0; i < Array.getLength(array); i++) {
                    accept(Array.get(array, i));
                }
            }
            case Record record -> record(record);
            case Map<?, ?> map -> map(map);
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

    private void record(Record record) {
        Class<?> type = record.getClass();
        List<Column> tableColumns = tableColumns(type);
        if (tableColumns == null) {
            endTable();
            listRecord(record);
            return;
        }
        if (type != tableType) {
            endTable();
            tableType = type;
            columns = tableColumns;
        }
        String[] row = new String[columns.size()];
        for (int i = 0; i < row.length; i++) {
            row[i] = cell(read(columns.get(i).accessor(), record), columns.get(i).bytes());
        }
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
            Class<?> t = columns.get(i).accessor().getReturnType();
            rightAligned[i] = columns.get(i).bytes() || Number.class.isAssignableFrom(t)
                    || (t.isPrimitive() && t != boolean.class && t != char.class);
        }
        for (String[] row : sample) {
            for (int i = 0; i < n; i++) {
                widths[i] = Math.max(widths[i], row[i].length());
            }
        }
        fitToWidth();
        String[] header = new String[n];
        String[] rule = new String[n];
        for (int i = 0; i < n; i++) {
            header[i] = columns.get(i).name();
            rule[i] = "-".repeat(Math.min(header[i].length(), widths[i]));
        }
        printRow(header);
        printRow(rule);
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

    private void printRow(String[] cells) {
        var line = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            String text = truncate(cells[i], widths[i]);
            boolean last = i == cells.length - 1;
            if (rightAligned[i]) {
                line.append(" ".repeat(widths[i] - text.length())).append(text);
            } else {
                line.append(text);
                if (!last) {
                    line.append(" ".repeat(widths[i] - text.length()));
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
        RecordComponent[] components = record.getClass().getRecordComponents();
        int nameWidth = Arrays.stream(components).mapToInt(c -> c.getName().length()).max().orElse(0);
        for (RecordComponent c : components) {
            out.println(("%-" + nameWidth + "s : %s").formatted(c.getName(),
                    cell(read(c.getAccessor(), record), c.isAnnotationPresent(Bytes.class))));
        }
        out.println();
    }

    private void map(Map<?, ?> map) {
        endTable();
        int keyWidth = Math.max(3, map.keySet().stream().mapToInt(k -> Values.text(k).length()).max().orElse(0));
        out.println(("%-" + keyWidth + "s  %s").formatted("clé", "valeur"));
        out.println(("%-" + keyWidth + "s  %s").formatted("---", "------"));
        map.forEach((k, v) -> out.println(("%-" + keyWidth + "s  %s").formatted(Values.text(k), cell(v, false))));
    }

    /** Table columns for this type, or {@code null} if it is displayed as a list. */
    private static List<Column> tableColumns(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        Display display = type.getAnnotation(Display.class);
        List<Column> result = new ArrayList<>();
        if (display != null) {
            for (String name : display.columns()) {
                Arrays.stream(components).filter(c -> c.getName().equals(name)).findFirst()
                        .ifPresent(c -> result.add(column(c)));
            }
            return result.isEmpty() ? null : result;
        }
        if (components.length == 0 || components.length > MAX_TABLE_COLUMNS) {
            return null;
        }
        for (RecordComponent c : components) {
            result.add(column(c));
        }
        return result;
    }

    private static Column column(RecordComponent c) {
        Method accessor = c.getAccessor();
        accessor.trySetAccessible();
        return new Column(c.getName(), accessor, c.isAnnotationPresent(Bytes.class));
    }

    private static Object read(Method accessor, Record record) {
        try {
            accessor.trySetAccessible();
            return accessor.invoke(record);
        } catch (ReflectiveOperationException e) {
            return "?";
        }
    }

    /** Value on one line, in readable format. */
    static String cell(Object value, boolean bytes) {
        return switch (value) {
            case null -> "";
            case Number n when bytes -> humanBytes(n.longValue());
            case Instant instant -> DATE_TIME.format(instant.atZone(ZoneId.systemDefault()));
            case Duration d -> Values.text(d);
            case String s -> s.replace('\n', ' ').replace('\r', ' ');
            default -> Values.text(value).replace('\n', ' ');
        };
    }

    /** {@code 14 520} → {@code 14,2 KB} (multiples of 1024, locale's decimal separator). */
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
        return String.format(Locale.getDefault(Locale.Category.FORMAT), "%.1f %s", value, units[unit]);
    }
}
