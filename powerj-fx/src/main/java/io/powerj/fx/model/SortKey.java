package io.powerj.fx.model;

import java.util.Objects;

/** One column to sort by, and in which direction. */
public record SortKey(String column, SortDirection direction) {

    public SortKey {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(direction, "direction");
    }
}
