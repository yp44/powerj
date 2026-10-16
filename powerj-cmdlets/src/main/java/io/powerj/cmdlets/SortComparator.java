package io.powerj.cmdlets;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.function.Consumer;

/**
 * Order of {@code sort} (specification FR-67): {@code null} first, numbers by value whatever their class, strings
 * (optionally ignoring case), then {@code Comparable}s of the same class. Other values are compared by their text, and
 * the first such comparison is reported once.
 */
final class SortComparator implements Comparator<Object> {

    private final boolean ignoreCase;
    private final Consumer<String> errors;
    private volatile boolean reported;

    /**
     * @param ignoreCase compares strings with {@link String#CASE_INSENSITIVE_ORDER}
     * @param errors     receives the non-blocking error about values that cannot be compared
     */
    SortComparator(boolean ignoreCase, Consumer<String> errors) {
        this.ignoreCase = ignoreCase;
        this.errors = errors;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public int compare(Object left, Object right) {
        if (left == null || right == null) {
            return left == null ? (right == null ? 0 : -1) : 1;
        }
        if (left instanceof Number a && right instanceof Number b) {
            return compareNumbers(a, b);
        }
        if (left instanceof String a && right instanceof String b) {
            return ignoreCase ? String.CASE_INSENSITIVE_ORDER.compare(a, b) : a.compareTo(b);
        }
        if (left instanceof Comparable comparable
                && (left.getClass().isInstance(right) || right.getClass().isInstance(left))) {
            return comparable.compareTo(right);
        }
        if (!reported) {
            reported = true;
            errors.accept(Messages.get("sort.not.comparable", left.getClass().getSimpleName(),
                    right.getClass().getSimpleName()));
        }
        String a = String.valueOf(left);
        String b = String.valueOf(right);
        return ignoreCase ? String.CASE_INSENSITIVE_ORDER.compare(a, b) : a.compareTo(b);
    }

    /** Compares two numbers by value; {@code NaN} after every number. */
    static int compareNumbers(Number a, Number b) {
        if (isIntegral(a) && isIntegral(b)) {
            return Long.compare(a.longValue(), b.longValue());
        }
        boolean aNaN = isNaN(a);
        boolean bNaN = isNaN(b);
        if (aNaN || bNaN) {
            return Boolean.compare(aNaN, bNaN);
        }
        if (isInfinite(a) || isInfinite(b)) {
            return Double.compare(a.doubleValue(), b.doubleValue());
        }
        return decimal(a).compareTo(decimal(b));
    }

    private static boolean isIntegral(Number n) {
        return n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte;
    }

    private static boolean isNaN(Number n) {
        return (n instanceof Double || n instanceof Float) && Double.isNaN(n.doubleValue());
    }

    private static boolean isInfinite(Number n) {
        return (n instanceof Double || n instanceof Float) && Double.isInfinite(n.doubleValue());
    }

    private static BigDecimal decimal(Number n) {
        return switch (n) {
            case BigDecimal d -> d;
            case BigInteger i -> new BigDecimal(i);
            case Double d -> BigDecimal.valueOf(d);
            case Float f -> new BigDecimal(f.toString());
            default -> isIntegral(n) ? BigDecimal.valueOf(n.longValue()) : new BigDecimal(n.toString());
        };
    }
}
