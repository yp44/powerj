package io.powerj.fx.model;

import java.util.List;
import java.util.Map;

/**
 * One received object, read: its columns, its row, and the properties that could not be read (their value is
 * {@code null} in the row).
 *
 * @param errors the reason, by column, for each property that failed
 */
public record Reading(List<ColumnSpec> columns, GridRow row, Map<String, String> errors) {

    public Reading {
        columns = List.copyOf(columns);
        errors = Map.copyOf(errors);
    }
}
