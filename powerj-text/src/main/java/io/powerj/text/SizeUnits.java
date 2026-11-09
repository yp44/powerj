package io.powerj.text;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * The units of a size: {@code b kb mb gb tb}, multiples of 1024, in lower case (specification FR-19). The language reads
 * {@code 2.5mb} as a literal and the filter of a column of the grid reads {@code >2,5 mb}: they do not accept the same
 * text (the filter takes a sign, a comma and a space), but they agree on what a unit is worth, and that is defined here.
 */
public final class SizeUnits {

    /** The units as a regular expression alternation, to build a pattern: the case matters. */
    public static final String PATTERN = "b|kb|mb|gb|tb";

    private static final BigInteger STEP = BigInteger.valueOf(1024);

    private SizeUnits() {
    }

    /** The power of 1024 of a unit ({@code b} is 0, {@code kb} 1… {@code tb} 4); -1 if it is none ({@code MB} is none). */
    public static int power(String unit) {
        if (unit == null) {
            return -1;
        }
        return switch (unit) {
            case "b" -> 0;
            case "kb" -> 1;
            case "mb" -> 2;
            case "gb" -> 3;
            case "tb" -> 4;
            default -> -1;
        };
    }

    /** {@code true} if {@code text} is exactly one of the units. */
    public static boolean isUnit(String text) {
        return power(text) >= 0;
    }

    /**
     * The number of bytes in one {@code unit}.
     *
     * @throws IllegalArgumentException if {@code unit} is none
     */
    public static BigDecimal factor(String unit) {
        int power = power(unit);
        if (power < 0) {
            throw new IllegalArgumentException("not a unit of a size: " + unit);
        }
        return new BigDecimal(STEP.pow(power));
    }

    /**
     * {@code number} units as an exact number of bytes (not rounded: the language rounds to the byte, a filter compares
     * the exact value).
     *
     * @throws IllegalArgumentException if {@code unit} is none
     */
    public static BigDecimal bytes(BigDecimal number, String unit) {
        return number.multiply(factor(unit));
    }
}
