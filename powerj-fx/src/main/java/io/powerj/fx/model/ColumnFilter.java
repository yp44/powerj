package io.powerj.fx.model;

/** A condition on the value of one cell. */
@FunctionalInterface
public interface ColumnFilter {

    boolean test(Object value);
}
