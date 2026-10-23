package io.powerj.fx.model;

/**
 * The comparison chosen in the list that sits before the filter of a numeric column. It goes with a plain value
 * ({@code 228}, {@code 2.2kb}); a text that already has its own operator ({@code >5}, {@code =3}, {@code !7},
 * {@code 1..9}, {@code /regex/}) is taken as it is typed.
 */
public enum FilterOperator {

    EQUAL("==", "="),
    LESS("<", "<"),
    GREATER(">", ">"),
    LESS_OR_EQUAL("<=", "<="),
    GREATER_OR_EQUAL(">=", ">=");

    private final String symbol;
    private final String prefix;

    FilterOperator(String symbol, String prefix) {
        this.symbol = symbol;
        this.prefix = prefix;
    }

    /** What the list shows. */
    public String symbol() {
        return symbol;
    }

    /**
     * The expression of the filter: the operator and the value. A text that is not a plain value, or empty, is returned
     * as it is.
     */
    public String apply(String text) {
        String value = text == null ? "" : text.strip();
        return value.isEmpty() || !isPlainValue(value) ? value : prefix + value;
    }

    /** {@code true} if {@code text} has no operator of its own. */
    static boolean isPlainValue(String text) {
        return !(text.startsWith(">") || text.startsWith("<") || text.startsWith("=") || text.startsWith("!")
                || text.startsWith("/") || text.contains(".."));
    }

    @Override
    public String toString() {
        return symbol;
    }
}
