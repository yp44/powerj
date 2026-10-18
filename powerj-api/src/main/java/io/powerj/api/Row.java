package io.powerj.api;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Object made of named columns, produced by {@code select} (specification FR-69): an immutable {@code Map} of column
 * name → value that keeps the order of the columns. The shell reads a column like a property ({@code $_.kb}) and
 * displays successive rows with the same columns as a table.
 */
public final class Row extends AbstractMap<String, Object> {

    private final Map<String, Object> values;
    private final Set<String> sizeColumns;

    private Row(Map<String, ?> values, Set<String> sizeColumns) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        this.sizeColumns = Set.copyOf(sizeColumns);
    }

    /** Row of these columns, in the iteration order of {@code values} ({@code null} values allowed). */
    public static Row of(Map<String, ?> values) {
        return new Row(values, Set.of());
    }

    /**
     * Row of these columns, of which {@code sizeColumns} hold sizes in bytes, displayed in readable form like a
     * {@link Bytes} component ({@code 14.2 KB}).
     */
    public static Row of(Map<String, ?> values, Set<String> sizeColumns) {
        return new Row(values, sizeColumns);
    }

    /** Columns displayed as sizes in bytes. */
    public Set<String> sizeColumns() {
        return sizeColumns;
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return values.entrySet();
    }

    @Override
    public Object get(Object key) {
        return values.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
        return values.containsKey(key);
    }
}
