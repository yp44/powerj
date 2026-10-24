package io.powerj.fx.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A line of the table: the values of the columns of one received object, read when it arrived, and the object itself
 * (what {@code --pass-thru} gives back, specification FR-77).
 *
 * @param source   the object received
 * @param values   value of each column the object has, by name; a column it lacks has no entry
 * @param position rank of arrival, from 0: rows that are equal for the sort keep this order, whatever the sorts that
 *                 came before
 */
public record GridRow(Object source, Map<String, Object> values, int position) {

    public GridRow {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** A row whose rank of arrival is not known yet: {@link GridModel.Builder} gives it. */
    public GridRow(Object source, Map<String, Object> values) {
        this(source, values, 0);
    }

    /** The same row, arrived at {@code position}. */
    GridRow withPosition(int position) {
        return new GridRow(source, values, position);
    }

    /** Value of a column, {@code null} if the object has none. */
    public Object get(String column) {
        return values.get(column);
    }
}
