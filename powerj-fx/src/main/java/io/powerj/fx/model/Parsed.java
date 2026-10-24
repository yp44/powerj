package io.powerj.fx.model;

/**
 * What a filter expression means.
 *
 * @param filter  the condition, {@code null} if the expression filters nothing (empty, or an operator alone) or is
 *                invalid
 * @param negated {@code true} if the rows to keep are the ones that do <b>not</b> satisfy {@code filter}
 * @param error   why the expression is invalid, {@code null} if it is valid
 */
public record Parsed(ColumnFilter filter, boolean negated, String error) {

    /** Filters nothing. */
    public static final Parsed NONE = new Parsed(null, false, null);

    static Parsed of(ColumnFilter filter) {
        return new Parsed(filter, false, null);
    }

    static Parsed not(ColumnFilter filter) {
        return new Parsed(filter, true, null);
    }

    static Parsed invalid(String error) {
        return new Parsed(null, false, error);
    }

    public boolean isValid() {
        return error == null;
    }

    /** {@code true} if there is a condition to apply. */
    public boolean isActive() {
        return filter != null;
    }

    /** Whether a cell is kept. A filter that filters nothing keeps everything. */
    public boolean keeps(Object value) {
        return filter == null || filter.test(value) != negated;
    }
}
