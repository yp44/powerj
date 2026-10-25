package io.powerj.cmdlets;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.function.Consumer;

/**
 * Order of {@code sort} (specification FR-67). It is a total order, so that {@code List.sort} never sees an
 * inconsistent comparator (review C-16): the values are first ordered by <em>kind</em>, then by value within a kind.
 * <ol>
 *   <li>{@code null};</li>
 *   <li>numbers, by value whatever their class, {@code NaN} last;</li>
 *   <li>strings, optionally ignoring case;</li>
 *   <li>other {@code Comparable}s, grouped by class (the groups in the order of the class names), each group by
 *       {@code compareTo};</li>
 *   <li>everything else, by its text.</li>
 * </ol>
 * Values of different kinds, and values that can only be compared by their text, are reported once.
 * A comparator that ordered mixed values by their text (3 &lt; 20, 20 &lt; "25", "25" &lt; 3) had no consistent
 * answer, and {@code TimSort} rejected it.
 */
final class SortComparator implements Comparator<Object> {

    private static final int NULL = 0;
    private static final int NUMBER = 1;
    private static final int STRING = 2;
    private static final int COMPARABLE = 3;
    private static final int OTHER = 4;

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
        int leftKind = kind(left);
        int rightKind = kind(right);
        if (leftKind != rightKind) {
            if (leftKind != NULL && rightKind != NULL) {
                report("sort.mixed.types", left, right);
            }
            return Integer.compare(leftKind, rightKind);
        }
        return switch (leftKind) {
            case NULL -> 0;
            case NUMBER -> compareNumbers((Number) left, (Number) right);
            case STRING -> text((String) left, (String) right);
            case COMPARABLE -> {
                Class<?> leftGroup = group(left);
                Class<?> rightGroup = group(right);
                if (leftGroup == rightGroup) {
                    yield ((Comparable) left).compareTo(right);
                }
                report("sort.mixed.types", left, right);
                yield leftGroup.getName().compareTo(rightGroup.getName());
            }
            default -> {
                report("sort.not.comparable", left, right);
                yield text(String.valueOf(left), String.valueOf(right));
            }
        };
    }

    private static int kind(Object value) {
        return switch (value) {
            case null -> NULL;
            case Number _ -> NUMBER;
            case String _ -> STRING;
            case Comparable<?> _ -> COMPARABLE;
            default -> OTHER;
        };
    }

    /** Comparables are compared with those of their own class only; the constants of an enum share their enum. */
    private static Class<?> group(Object comparable) {
        return comparable instanceof Enum<?> constant ? constant.getDeclaringClass() : comparable.getClass();
    }

    private int text(String a, String b) {
        return ignoreCase ? String.CASE_INSENSITIVE_ORDER.compare(a, b) : a.compareTo(b);
    }

    private void report(String key, Object left, Object right) {
        if (!reported) {
            reported = true;
            errors.accept(Messages.get(key, left.getClass().getSimpleName(), right.getClass().getSimpleName()));
        }
    }

    /**
     * Compares two numbers by value; {@code NaN} after every number, infinities at the ends. A total order: every
     * number has one place, whatever the classes it is compared with.
     */
    static int compareNumbers(Number a, Number b) {
        if (isIntegral(a) && isIntegral(b)) {
            return Long.compare(a.longValue(), b.longValue());
        }
        int aSpecial = special(a);
        int bSpecial = special(b);
        if (aSpecial != 0 || bSpecial != 0) {
            return Integer.compare(aSpecial, bSpecial);
        }
        return decimal(a).compareTo(decimal(b));
    }

    /** {@code -1} for -Infinity, {@code 1} for +Infinity, {@code 2} for NaN, {@code 0} for a number with a value. */
    private static int special(Number n) {
        if (!(n instanceof Double || n instanceof Float)) {
            return 0;
        }
        double value = n.doubleValue();
        if (Double.isNaN(value)) {
            return 2;
        }
        return Double.isInfinite(value) ? (value < 0 ? -1 : 1) : 0;
    }

    private static boolean isIntegral(Number n) {
        return n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte;
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
