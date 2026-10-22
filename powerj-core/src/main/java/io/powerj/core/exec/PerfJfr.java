package io.powerj.core.exec;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Is the profiler there, and how finely does it sample? The module {@code jdk.jfr} is optional ({@code requires static}):
 * a custom runtime may lack it, and perf mode then works without the profiler. This class names no type of the module,
 * so it can always be loaded.
 */
final class PerfJfr {

    /** Variable of the session environment ({@code env --set} in {@code profile.pj}) that gives the sampling period. */
    static final String SAMPLE_PERIOD_VARIABLE = "POWERJ_PERF_JFR_SAMPLEPERIOD";
    static final Duration DEFAULT_SAMPLE_PERIOD = Duration.ofMillis(10);
    /** The profiler does not sample more often than that: it silently raises a shorter period to it. */
    static final Duration MINIMUM_SAMPLE_PERIOD = Duration.ofMillis(1);

    private static final Pattern PERIOD = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(ns|us|µs|ms|s)?");

    private PerfJfr() {
    }

    static boolean available() {
        return ModuleLayer.boot().findModule("jdk.jfr").isPresent();
    }

    /**
     * Reads a sampling period: a number with a unit {@code us}, {@code ms} or {@code s} ({@code 2ms}, {@code 500us},
     * {@code 0.5s}); without a unit, milliseconds.
     *
     * @throws IllegalArgumentException if the text is not a duration, or is zero
     */
    static Duration parseSamplePeriod(String text) {
        Matcher matcher = PERIOD.matcher(text.strip().toLowerCase(Locale.ROOT));
        if (!matcher.matches()) {
            throw new IllegalArgumentException(text);
        }
        double number = Double.parseDouble(matcher.group(1));
        long nanos = Math.round(number * switch (matcher.group(2) == null ? "ms" : matcher.group(2)) {
            case "ns" -> 1.0;
            case "us", "µs" -> 1_000.0;
            case "s" -> 1_000_000_000.0;
            default -> 1_000_000.0;
        });
        if (nanos <= 0) {
            throw new IllegalArgumentException(text);
        }
        return Duration.ofNanos(nanos);
    }
}
