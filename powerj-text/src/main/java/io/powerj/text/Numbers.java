package io.powerj.text;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * What a command that adds or compares numbers needs to know about a {@link Number}: whether it is an integer, whether it is
 * finite, and its exact value. {@code measure} and {@code sort} each had their own copy of these, and the copies had parted:
 * a {@code BigInteger} was integral for one and not the other, and an adder holding 1.5 became 1 in the sum and 1.5 in the
 * sort (audit v2, C-07).
 */
public final class Numbers {

    private Numbers() {
    }

    /** Is it an integer that fits in a {@code long}? ({@code Long}, {@code Integer}, {@code Short}, {@code Byte} and the atomics.) */
    public static boolean fitsLong(Number n) {
        return n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte
                || n instanceof AtomicInteger || n instanceof AtomicLong || n instanceof LongAdder;
    }

    /** Is it an integer of any size? */
    public static boolean isIntegral(Number n) {
        return fitsLong(n) || n instanceof BigInteger;
    }

    /** Has it a value ({@code false} for {@code NaN} and the infinities)? */
    public static boolean isFinite(Number n) {
        return isIntegral(n) || n instanceof BigDecimal || Double.isFinite(n.doubleValue());
    }

    /**
     * Its value as a {@code BigDecimal}: exact for an integer, the shortest text that reads back as the same double or
     * float otherwise (so {@code 0.1} is {@code 0.1}), and for any other class the value it prints.
     *
     * @throws ArithmeticException if it is not finite
     */
    public static BigDecimal decimal(Number n) {
        return switch (n) {
            case BigDecimal d -> d;
            case BigInteger i -> new BigDecimal(i);
            case Double d when Double.isFinite(d) -> BigDecimal.valueOf(d);
            case Float f when Float.isFinite(f) -> new BigDecimal(f.toString());
            default -> {
                if (fitsLong(n)) {
                    yield BigDecimal.valueOf(n.longValue());
                }
                if (!isFinite(n)) {
                    throw new ArithmeticException("not a finite number: " + n);
                }
                try {
                    yield new BigDecimal(n.toString()); // another Number (an adder, a user's class): the value it prints
                } catch (NumberFormatException _) {
                    yield BigDecimal.valueOf(n.doubleValue());
                }
            }
        };
    }
}
