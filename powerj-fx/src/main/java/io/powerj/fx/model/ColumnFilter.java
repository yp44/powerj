package io.powerj.fx.model;

/** A condition on the value of one cell. */
@FunctionalInterface
public interface ColumnFilter {

    boolean test(Object value);

    /** How many cells this filter gave up on because they took too long; only a regex can. */
    default int abandoned() {
        return 0;
    }
}
