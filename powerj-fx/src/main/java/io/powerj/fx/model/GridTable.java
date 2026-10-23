package io.powerj.fx.model;

import java.util.List;
import java.util.Objects;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;

/**
 * The rows the window shows: the rows of the {@link GridModel} that pass the current {@link FilterSpec}, in the order
 * of the current {@link SortSpec}. It only
 * needs {@code javafx.base} (no display), so it is tested without a window; the table of the window shows
 * {@link #rows()} and tells it how to sort.
 */
public final class GridTable {

    private final GridModel model;
    private final ObservableList<GridRow> source;
    private final FilteredList<GridRow> filtered;
    private final SortedList<GridRow> sorted;
    private SortSpec sort = SortSpec.NONE;

    public GridTable(GridModel model) {
        this.model = Objects.requireNonNull(model, "model");
        this.source = FXCollections.observableArrayList(model.rows());
        this.filtered = new FilteredList<>(source);
        this.sorted = new SortedList<>(filtered);
    }

    public List<ColumnSpec> columns() {
        return model.columns();
    }

    /** Rows received, whatever the filters. */
    public int total() {
        return source.size();
    }

    /** The rows to show, filtered and sorted: it changes when {@link #filter} or {@link #sort(SortSpec)} is called. */
    public ObservableList<GridRow> rows() {
        return sorted;
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
