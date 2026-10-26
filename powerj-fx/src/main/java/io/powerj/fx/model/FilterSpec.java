package io.powerj.fx.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Which rows are shown: the rows that satisfy the condition of every filtered column, and, if there is a search text,
 * that have it in some column. Built by {@link FilterEditor}.
 */
public final class FilterSpec {

    /** Everything is shown. */
    public static final FilterSpec NONE = new FilterSpec(Map.of(), Map.of());

    private final Map<String, Parsed> columns;
    private final Map<String, Parsed> search;

    /**
     * @param columns the filter of each filtered column
     * @param search  the search text, parsed for each column (it is read as the text of that column's cells)
     */
    FilterSpec(Map<String, Parsed> columns, Map<String, Parsed> search) {
        this.columns = new LinkedHashMap<>(columns);
        this.search = new LinkedHashMap<>(search);
    }

    /** How many cells the filters gave up on so far: the rows they hid may include rows that match. */
    public int abandoned() {
        int total = 0;
        for (Parsed parsed : columns.values()) {
            total += parsed.abandoned();
        }
        for (Parsed parsed : search.values()) {
            total += parsed.abandoned();
        }
        return total;
    }

    public boolean isEmpty() {
        return columns.isEmpty() && search.isEmpty();
    }

    /** The rows to show, or {@code null} for all of them. */
    public Predicate<GridRow> predicate() {
        return isEmpty() ? null : this::keeps;
    }

    private boolean keeps(GridRow row) {
        for (Map.Entry<String, Parsed> column : columns.entrySet()) {
            if (!column.getValue().keeps(row.get(column.getKey()))) {
                return false;
            }
        }
        if (search.isEmpty()) {
            return true;
        }
        // "!text" in the search box: no column has the text.
        boolean negated = search.values().iterator().next().negated();
        boolean found = false;
        for (Map.Entry<String, Parsed> column : search.entrySet()) {
            Parsed parsed = column.getValue();
            if (parsed.filter().test(row.get(column.getKey()))) {
                found = true;
                break;
            }
        }
        return found != negated;
    }
}
