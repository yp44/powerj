package io.powerj.fx.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A line of the table: the values of the columns of one received object, read when it arrived, and the object itself
 * (what {@code --pass-thru} gives back, specification FR-77).
 *
 * @param source the object received
 * @param values value of each column the object has, by name; a column it lacks has no entry
 */
public record GridRow(Object source, Map<String, Object> values) {

    public GridRow {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** Value of a column, {@code null} if the object has none. */
    public Object get(String column) {
        return values.get(column);
    }
}
