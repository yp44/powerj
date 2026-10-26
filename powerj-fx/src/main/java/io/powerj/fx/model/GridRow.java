package io.powerj.fx.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A line of the table: the values of the columns of one received object, read when it arrived, and the object itself
 * (what {@code --pass-thru} gives back, specification FR-77).
 * <p>
 * The values are an array indexed by a {@link RowShape} that the rows of one type share, not a map per row.
 */
public final class GridRow {

    private final Object source;
    private final RowShape shape;
    private final Object[] cells;
    private final int position;

    /**
     * @param source   the object received, {@code null} if nobody will ask for it again
     * @param values   value of each column the object has, by name; a column it lacks has no entry
     * @param position rank of arrival, from 0: rows that are equal for the sort keep this order, whatever the sorts that
     *                 came before
     */
    public GridRow(Object source, Map<String, Object> values, int position) {
        this(source, List.copyOf(values.keySet()), values, position);
    }

    private GridRow(Object source, List<String> names, Map<String, Object> values, int position) {
        this(source, RowShape.of(names), names.stream().map(values::get).toArray(), position);
    }

    /** A row whose rank of arrival is not known yet: {@link GridModel.Builder} gives it. */
    public GridRow(Object source, Map<String, Object> values) {
        this(source, values, 0);
    }

    /** @param cells the values in the order of the shape: the row owns the array and nobody changes it */
    GridRow(Object source, RowShape shape, Object[] cells, int position) {
        assert shape.size() == cells.length;
        this.source = source;
        this.shape = shape;
        this.cells = cells;
        this.position = position;
    }

    /** The object received; {@code null} if the table was told not to keep it (it is only needed by {@code --pass-thru}). */
    public Object source() {
        return source;
    }

    /** Rank of arrival, from 0. */
    public int position() {
        return position;
    }

    /** The same row, arrived at {@code position}: it shares the cells of this one. */
    GridRow withPosition(int position) {
        return new GridRow(source, shape, cells, position);
    }

    /** Value of a column, {@code null} if the object has none. */
    public Object get(String column) {
        int at = shape.indexOf(column);
        return at < 0 ? null : cells[at];
    }

    /** The shape of the row: rows of one type share it. */
    RowShape shape() {
        return shape;
    }

    /** The values by name, in the order of the columns; a read-only copy made at each call, for tests and logs. */
    public Map<String, Object> values() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < cells.length; i++) {
            values.put(shape.names().get(i), cells[i]);
        }
        return Collections.unmodifiableMap(values);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GridRow row && position == row.position && Objects.equals(source, row.source)
                && shape.names().equals(row.shape.names()) && Arrays.equals(cells, row.cells);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, shape.names(), Arrays.hashCode(cells), position);
    }

    @Override
    public String toString() {
        return "GridRow[source=" + source + ", values=" + values() + ", position=" + position + "]";
    }
}
