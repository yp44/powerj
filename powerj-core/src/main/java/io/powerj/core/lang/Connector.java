package io.powerj.core.lang;

/** How a statement is chained to the previous one (specification FR-04c). */
public enum Connector {
    /** Start of line or {@code ;}: always executed. */
    ALWAYS,
    /** {@code &&}: executed if the previous statement succeeded. */
    IF_SUCCESS,
    /** {@code ||}: executed if the previous statement failed. */
    IF_FAILURE
}
