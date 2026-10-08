package io.powerj.core.lang;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unit literals (specification FR-19): sizes {@code 512b 2kb 500mb 1gb 1tb} (multiples of 1024,
 * {@code Long} value in bytes) and durations {@code 250ms 30s 5m 2h 7d} ({@link Duration}).
 */
public final class Units {

    private static final Pattern LITERAL = Pattern.compile("(\\d+(?:\\.\\d+)?)(b|kb|mb|gb|tb|ms|s|m|h|d)",
            Pattern.CASE_INSENSITIVE);

    private Units() {
    }

    /** Value of the literal, or empty if the text is not one. */
    public static Optional<Object> parse(String text) {
        Matcher m = LITERAL.matcher(text);
        if (!m.matches()) {
            return Optional.empty();
        }
        var number = new BigDecimal(m.group(1));
        String unit = m.group(2).toLowerCase(Locale.ROOT);
        return Optional.of(switch (unit) {
            case "b" -> bytes(number, 0);
            case "kb" -> bytes(number, 1);
            case "mb" -> bytes(number, 2);
            case "gb" -> bytes(number, 3);
            case "tb" -> bytes(number, 4);
            case "ms" -> Duration.ofNanos(nanos(number, 1_000_000L));
            case "s" -> Duration.ofNanos(nanos(number, 1_000_000_000L));
            case "m" -> Duration.ofNanos(nanos(number, 60_000_000_000L));
            case "h" -> Duration.ofNanos(nanos(number, 3_600_000_000_000L));
            case "d" -> Duration.ofNanos(nanos(number, 86_400_000_000_000L));
            default -> throw new IllegalStateException(unit);
        });
    }

    private static Long bytes(BigDecimal number, int power) {
        return number.multiply(BigDecimal.valueOf(1024).pow(power)).setScale(0, java.math.RoundingMode.HALF_UP)
                .longValueExact();
    }

    private static long nanos(BigDecimal number, long factor) {
        return number.multiply(BigDecimal.valueOf(factor)).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }
}
