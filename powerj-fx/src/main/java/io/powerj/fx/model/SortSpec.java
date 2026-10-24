package io.powerj.fx.model;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * How the table is sorted: the columns to sort by, the first one being the main key. The empty spec leaves the rows in
 * the order they arrived.
 */
public record SortSpec(List<SortKey> keys) {

    /** No sort: the order of arrival. */
    public static final SortSpec NONE = new SortSpec(List.of());

    public SortSpec {
        keys = List.copyOf(keys);
        Set<String> seen = new HashSet<>();
        for (SortKey key : keys) {
            if (!seen.add(key.column())) {
                throw new IllegalArgumentException("column sorted twice: " + key.column());
            }
        }
    }

    public boolean isEmpty() {
        return keys.isEmpty();
    }

    /**
     * The order of the rows, or {@code null} for the order of arrival. {@code null} values are always last, ascending
     * or descending. Rows that are equal for every key stay in the order they arrived, whatever the sorts applied
     * before: the same spec always gives the same order.
     */
    public Comparator<GridRow> comparator() {
        Comparator<GridRow> result = null;
        for (SortKey key : keys) {
            Comparator<GridRow> next = (a, b) -> compare(a.get(key.column()), b.get(key.column()), key.direction());
            result = result == null ? next : result.thenComparing(next);
        }
        return result == null ? null : result.thenComparingInt(GridRow::position);
    }

    private static int compare(Object a, Object b, SortDirection direction) {
        if (a == null || b == null) {
            return a == b ? 0 : a == null ? 1 : -1;
        }
        int order = ValueOrder.INSTANCE.compare(a, b);
        return direction == SortDirection.ASCENDING ? order : -order;
    }
}
