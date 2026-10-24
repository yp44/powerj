package io.powerj.fx.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;

/**
 * The rows the window shows: the rows received that pass the current {@link FilterSpec}, in the order of the current
 * {@link SortSpec}. Rows and columns can keep arriving ({@link #append}), the table follows with the same filter and the
 * same sort. It only needs {@code javafx.base} (no display), so it is tested without a window; the table of the window
 * shows {@link #rows()} and {@link #columns()}, and is only to be touched from the toolkit thread.
 */
public final class GridTable {

    private final ObservableList<ColumnSpec> columns = FXCollections.observableArrayList();
    /**
     * One read-only view, kept: the listeners of such a view are held weakly by the list behind it, so a view made at each
     * call would lose them as soon as it is collected.
     */
    private final ObservableList<ColumnSpec> columnsView = FXCollections.unmodifiableObservableList(columns);
    private final Map<String, ColumnSpec> known = new LinkedHashMap<>();
    private final ObservableList<GridRow> source = FXCollections.observableArrayList();
    private final FilteredList<GridRow> filtered = new FilteredList<>(source);
    private final SortedList<GridRow> sorted = new SortedList<>(filtered);
    private SortSpec sort = SortSpec.NONE;

    /** An empty table, to be filled with {@link #append}. */
    public GridTable() {
    }

    /** A table that already holds a whole model. */
    public GridTable(GridModel model) {
        Objects.requireNonNull(model, "model");
        model.columns().forEach(column -> known.put(column.name(), column));
        columns.addAll(model.columns());
        source.addAll(model.rows());
    }

    /**
     * The columns, in order of first appearance. A new column is added at the end; a column whose kind became clearer
     * (it held only empty cells, then numbers) is replaced where it is.
     */
    public ObservableList<ColumnSpec> columns() {
        return columnsView;
    }

    /** Rows received, whatever the filters. */
    public int total() {
        return source.size();
    }

    /** The rows to show, filtered and sorted: it changes when {@link #filter} or {@link #sort(SortSpec)} is called. */
    public ObservableList<GridRow> rows() {
        return sorted;
    }

    /**
     * Adds rows that arrived. The rows come first, then the new columns: when a listener hears of a column, the rows are
     * there to measure it.
     */
    public void append(List<Reading> readings) {
        List<GridRow> rows = new ArrayList<>(readings.size());
        int position = source.size();
        for (Reading reading : readings) {
            rows.add(reading.row().withPosition(position++));
            for (ColumnSpec column : reading.columns()) {
                known.merge(column.name(), column, ColumnSpec::merge);
            }
        }
        source.addAll(rows);
        List<ColumnSpec> now = new ArrayList<>(known.values());
        for (int i = 0; i < columns.size(); i++) {
            if (!columns.get(i).equals(now.get(i))) {
                columns.set(i, now.get(i));
            }
        }
        if (now.size() > columns.size()) {
            columns.addAll(now.subList(columns.size(), now.size()));
        }
    }

    public SortSpec sort() {
        return sort;
    }

    /** Sorts the rows; {@link SortSpec#NONE} brings back the order of arrival. */
    public void sort(SortSpec spec) {
        this.sort = Objects.requireNonNull(spec, "spec");
        sorted.setComparator(spec.comparator());
    }

    /** Shows only the rows that pass; {@link FilterSpec#NONE} shows them all. */
    public void filter(FilterSpec spec) {
        filtered.setPredicate(Objects.requireNonNull(spec, "spec").predicate());
    }
}
