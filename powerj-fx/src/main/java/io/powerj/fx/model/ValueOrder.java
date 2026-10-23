package io.powerj.fx.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.Temporal;
import java.util.Date;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The order of the values of a column, whatever they are: numbers by value (a size by its number of bytes, not by its
 * text), dates by time, booleans {@code false} first, texts without regard to case, anything else by its natural order
 * when it has one. A column that holds values of several kinds shows numbers first, then dates, booleans, and texts.
 */
final class ValueOrder implements Comparator<Object> {

    static final ValueOrder INSTANCE = new ValueOrder();

    private static final int NUMBER = 0;
    private static final int DATE = 1;
    private static final int BOOLEAN = 2;
    private static final int OTHER = 3;

    private ValueOrder() {
    }

    /** Both values are present: {@code null} is handled by the caller, which puts it last. */
    @Override
    public int compare(Object a, Object b) {
        int rankA = rank(a);
        int rankB = rank(b);
        if (rankA != rankB) {
            return Integer.compare(rankA, rankB);
        }
        return switch (rankA) {
            case NUMBER -> numbers((Number) a, (Number) b);
            case DATE -> dates(a, b);
            case BOOLEAN -> Boolean.compare((Boolean) a, (Boolean) b);
            default -> others(a, b);
        };
    }

    private static int rank(Object value) {
        if (value instanceof Number) {
            return NUMBER;
        }
        if (value instanceof Temporal || value instanceof Date) {
            return DATE;
        }
        return value instanceof Boolean ? BOOLEAN : OTHER;
    }

    private static int numbers(Number a, Number b) {
        if (isBig(a) || isBig(b)) {
            if (isFinite(a) && isFinite(b)) {
                return decimal(a).compareTo(decimal(b));
            }
        } else if (isIntegral(a) && isIntegral(b)) {
            return Long.compare(a.longValue(), b.longValue());
        }
        return Double.compare(a.doubleValue(), b.doubleValue());
    }

    private static boolean isIntegral(Number n) {
        return n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte
                || n instanceof AtomicInteger || n instanceof AtomicLong;
    }

    private static boolean isBig(Number n) {
        return n instanceof BigDecimal || n instanceof BigInteger;
    }

    private static boolean isFinite(Number n) {
        return !(n instanceof Double || n instanceof Float) || Double.isFinite(n.doubleValue());
    }

    private static BigDecimal decimal(Number n) {
        return switch (n) {
            case BigDecimal d -> d;
            case BigInteger i -> new BigDecimal(i);
            case Double d -> new BigDecimal(d);
            case Float f -> new BigDecimal(f.doubleValue());
            default -> BigDecimal.valueOf(n.longValue());
        };
    }

    @SuppressWarnings("unchecked")
    private static int dates(Object a, Object b) {
        if (a.getClass() == b.getClass() && a instanceof Comparable<?> comparable) {
            return ((Comparable<Object>) comparable).compareTo(b);
        }
        Instant instantA = instant(a);
        Instant instantB = instant(b);
        if (instantA != null && instantB != null) {
            return instantA.compareTo(instantB);
        }
        return text(a, b);
    }

    private static Instant instant(Object value) {
        return switch (value) {
            case Instant i -> i;
            case Date d -> d.toInstant();
            case java.time.ZonedDateTime z -> z.toInstant();
            case java.time.OffsetDateTime o -> o.toInstant();
            default -> null;
        };
    }

    @SuppressWarnings("unchecked")
    private static int others(Object a, Object b) {
        boolean plainText = a instanceof CharSequence || a instanceof Path || a instanceof Character;
        if (!plainText && a.getClass() == b.getClass() && a instanceof Comparable<?> comparable) {
            return ((Comparable<Object>) comparable).compareTo(b);
        }
        return text(a, b);
    }

    /** Without regard to case; two texts that only differ by their case keep a fixed order. */
    private static int text(Object a, Object b) {
        String textA = String.valueOf(a);
        String textB = String.valueOf(b);
        int result = String.CASE_INSENSITIVE_ORDER.compare(textA, textB);
        return result != 0 ? result : textA.compareTo(textB);
    }
}
