package io.powerj.fx.model;

import java.util.Objects;

/** A column of the table: its name (the header) and what it holds. */
public record ColumnSpec(String name, ColumnKind kind) {

    public ColumnSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
    }

    /** The column that two objects share: same name, merged kind. This one itself if the kind does not change. */
    ColumnSpec merge(ColumnSpec other) {
        ColumnKind merged = kind.merge(other.kind);
        return merged == kind ? this : new ColumnSpec(name, merged);
    }
}
