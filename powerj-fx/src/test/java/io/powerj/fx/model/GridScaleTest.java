package io.powerj.fx.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.ref.Reference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 100 000 rows, the default limit of {@code grid --max-rows} (NFR-04), through the model: what a row costs in memory, and
 * that reading, sorting and filtering them stays in the order of a second. The measures are in
 * {@code docs/quality/performance.md}; the budgets here are several times above them, so that a slow machine does not
 * fail them and a return to a map per row (about 500 bytes a row more) does.
 */
@Timeout(120)
class GridScaleTest {

    private static final int ROWS = 100_000;
    private static final String[] KINDS = {"file", "directory", "link"};

    /** Eight columns whose values are shared or cached boxes, so that what a row costs is the row. */
    record Entry(String kind, int depth, boolean hidden, byte level, short code, char letter, String group, boolean flag) {
    }

    private static final ClassValue<Map<String, Method>> ACCESSORS = new ClassValue<>() {
        @Override
        protected Map<String, Method> computeValue(Class<?> type) {
            Map<String, Method> accessors = new HashMap<>();
            for (var component : type.getRecordComponents()) {
                component.getAccessor().setAccessible(true);
                accessors.put(component.getName(), component.getAccessor());
            }
            return accessors;
        }
    };

    /** A property read that costs what the shell's does (cached accessors), so that the reader alone is measured. */
    private static Object read(Object target, String name) {
        try {
            return ACCESSORS.get(target.getClass()).get(name).invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Entry entry(int i) {
        return new Entry(KINDS[i % 3], i % 9, i % 13 == 0, (byte) (i % 100), (short) (i % 100), (char) ('a' + i % 26),
                "group", i % 2 == 0);
    }

    private static long used() {
        for (int i = 0; i < 4; i++) {
            System.gc();
        }
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static GridTable fill(ObjectReader reader) {
        GridTable table = new GridTable();
        List<Reading> batch = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            batch.add(reader.read(entry(i)));
            if (batch.size() == 20_000) { // the batch of the window
                table.append(batch);
                batch = new ArrayList<>();
            }
        }
        return table;
    }

    @Test
    void aRowOfEightColumnsCostsAboutAHundredBytes() {
        var reader = new ObjectReader(GridScaleTest::read, false, false);
        long before = used();

        GridTable table = fill(reader);

        long perRow = (used() - before) / ROWS;
        Reference.reachabilityFence(table);
        assertThat(table.total()).isEqualTo(ROWS);
        assertThat(perRow).as("bytes retained per row").isLessThan(250);
    }

    @Test
    void readingSortingAndFilteringTheDefaultLimitStaysInTheOrderOfASecond() {
        var reader = new ObjectReader(GridScaleTest::read, false, false);
        long start = System.nanoTime();

        GridTable table = fill(reader);
        long filled = System.nanoTime();
        table.sort(new SortSpec(List.of(new SortKey("depth", SortDirection.DESCENDING))));
        assertThat(table.rows().getFirst().get("depth")).isEqualTo(8);
        long sorted = System.nanoTime();
        var editor = new FilterEditor(table.columns());
        table.filter(editor.setColumn("kind", "=link"));
        long filtered = System.nanoTime();
        int links = table.rows().size();
        editor.setColumn("kind", "");
        table.filter(editor.setSearch("director"));
        long searched = System.nanoTime();

        assertThat(links).isEqualTo(ROWS / 3);
        assertThat(table.rows().size()).isEqualTo(ROWS / 3); // the directories: 1, 4, … 99 997
        assertThat(millis(start, filled)).as("reading and appending").isLessThan(15_000);
        assertThat(millis(filled, sorted)).as("sorting").isLessThan(15_000);
        assertThat(millis(sorted, filtered)).as("filtering a column").isLessThan(15_000);
        assertThat(millis(filtered, searched)).as("searching every column").isLessThan(15_000);
    }

    private static long millis(long from, long to) {
        return (to - from) / 1_000_000;
    }

    @Test
    void ticking100000RowsKeepsTheirOrderAndCountsThem() {
        var reader = new ObjectReader(GridScaleTest::read, false, false);
        GridTable table = fill(reader);
        var selection = new RowSelection(false);

        selection.selectAll(new ArrayList<>(table.rows()));

        assertThat(selection.count().get()).isEqualTo(ROWS);
        assertThat(selection.selected(null)).hasSize(ROWS).first().extracting(GridRow::position).isEqualTo(0);
    }
}
