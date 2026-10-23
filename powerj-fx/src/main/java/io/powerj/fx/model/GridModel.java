package io.powerj.fx.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the window shows: the columns, in order of first appearance, and one row per received object.
 *
 * @param columns union of the columns of every object
 */
public record GridModel(List<ColumnSpec> columns, List<GridRow> rows) {

    public GridModel {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
    }

    /** Collects the objects as they arrive. Not thread safe: one pipeline stage fills it. */
    public static final class Builder {

        private final Map<String, ColumnSpec> columns = new LinkedHashMap<>();
        private final List<GridRow> rows = new ArrayList<>();

        /** Adds a row; its columns join the table, a column known already keeps its place and merges its kind. */
        public Builder add(Reading reading) {
            for (ColumnSpec column : reading.columns()) {
                columns.merge(column.name(), column, ColumnSpec::merge);
            }
            rows.add(reading.row());
            return this;
        }

        public int size() {
            return rows.size();
        }

        public GridModel build() {
            return new GridModel(List.copyOf(columns.values()), rows);
        }
    }
}
