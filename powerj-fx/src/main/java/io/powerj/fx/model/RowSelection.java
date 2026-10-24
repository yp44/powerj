package io.powerj.fx.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.SimpleBooleanProperty;

/**
 * The rows that are checked in the window of {@code grid --pass-thru}: they are what the command gives back to the
 * pipeline. The state belongs to the row, not to the table, so a row that a filter hides or a sort moves keeps its tick.
 * It only needs {@code javafx.base}, so it is tested without a window.
 */
public final class RowSelection {

    private final boolean single;
    /** The tick of each row that has been shown or ticked; rows are told apart by identity. */
    private final Map<GridRow, BooleanProperty> properties = new IdentityHashMap<>();
    private final ReadOnlyIntegerWrapper count = new ReadOnlyIntegerWrapper();

    /** @param single only one row can be checked: checking a row unchecks the other */
    public RowSelection(boolean single) {
        this.single = single;
    }

    public boolean isSingle() {
        return single;
    }

    /** The tick of a row, to bind to its check box. */
    public BooleanProperty property(GridRow row) {
        return properties.computeIfAbsent(row, this::create);
    }

    private BooleanProperty create(GridRow row) {
        BooleanProperty tick = new SimpleBooleanProperty(false);
        tick.addListener((_, _, checked) -> changed(row, checked));
        return tick;
    }

    private void changed(GridRow row, boolean checked) {
        count.set(count.get() + (checked ? 1 : -1));
        if (checked && single) {
            for (Map.Entry<GridRow, BooleanProperty> other : properties.entrySet()) {
                if (other.getKey() != row && other.getValue().get()) {
                    other.getValue().set(false);
                }
            }
        }
    }

    public boolean isSelected(GridRow row) {
        BooleanProperty tick = properties.get(row);
        return tick != null && tick.get();
    }

    /** How many rows are checked. */
    public ReadOnlyIntegerProperty count() {
        return count.getReadOnlyProperty();
    }

    /** Checks these rows (nothing when only one row can be checked). The other rows are left as they are. */
    public void selectAll(Collection<GridRow> rows) {
        if (!single) {
            rows.forEach(row -> property(row).set(true));
        }
    }

    /** Unchecks these rows. The other rows are left as they are. */
    public void unselectAll(Collection<GridRow> rows) {
        for (GridRow row : rows) {
            BooleanProperty tick = properties.get(row);
            if (tick != null) {
                tick.set(false);
            }
        }
    }

    /**
     * The rows that are checked, filtered out or not, in the order of the table: {@code order} if there is a sort,
     * otherwise the order of arrival.
     */
    public List<GridRow> selected(Comparator<GridRow> order) {
        List<GridRow> rows = new ArrayList<>();
        properties.forEach((row, tick) -> {
            if (tick.get()) {
                rows.add(row);
            }
        });
        rows.sort(order == null ? Comparator.comparingInt(GridRow::position) : order);
        return rows;
    }
}
