package io.powerj.core.lang;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.powerj.text.SizeUnits;

/**
 * Unit literals (specification FR-19): sizes {@code 512b 2kb 500mb 1gb 1tb} (multiples of 1024,
 * {@code Long} value in bytes) and durations {@code 250ms 30s 5m 2h 7d} ({@link Duration}).
 */
public final class Units {

    /** The case of the unit matters: {@code 2mb}, not {@code 2MB}. */
    private static final Pattern LITERAL = Pattern.compile("(\\d+(?:\\.\\d+)?)(" + SizeUnits.PATTERN + "|ms|s|m|h|d)");

    /**
     * What a number looks like, read in lower case: digits, an optional fraction, and nothing, {@code L} or a unit. It is a test of
     * shape, not of value: {@code 5S} has the shape of a literal and is reported as one that is badly written.
     */
    private static final Pattern SHAPE = Pattern.compile(
            "\\d+(?:\\.\\d+)?(?:l|" + SizeUnits.PATTERN + "|ms|s|m|h|d)?");

    private Units() {
    }

    /**
     * Has the word the shape of a number or of a unit literal ({@code 42}, {@code 1.5}, {@code 3L}, {@code 7d}, {@code 2MB})? A word that
     * starts with a digit and has not ({@code 7z}, {@code 2to3}, {@code 7z.exe}) is the name of a program, not a literal.
     */
    public static boolean looksLikeLiteral(String word) {
        return SHAPE.matcher(word.toLowerCase(Locale.ROOT)).matches();
    }

    /** Value of the literal, or empty if the text is not one. */
    public static Optional<Object> parse(String text) {
        Matcher m = LITERAL.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        var number = new BigDecimal(m.group(1));
        String unit = m.group(2);
        if (SizeUnits.isUnit(unit)) {
            return Optional.of(bytes(number, unit));
        }
        return Optional.of(switch (unit) {
            case "ms" -> Duration.ofNanos(nanos(number, 1_000_000L));
            case "s" -> Duration.ofNanos(nanos(number, 1_000_000_000L));
            case "m" -> Duration.ofNanos(nanos(number, 60_000_000_000L));
            case "h" -> Duration.ofNanos(nanos(number, 3_600_000_000_000L));
            case "d" -> Duration.ofNanos(nanos(number, 86_400_000_000_000L));
            default -> throw new IllegalStateException(unit);
        });
    }

    private static Long bytes(BigDecimal number, String unit) {
        return SizeUnits.bytes(number, unit).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static long nanos(BigDecimal number, long factor) {
        return number.multiply(BigDecimal.valueOf(factor)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
