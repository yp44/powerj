package io.powerj.core.exec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Perf mode's Flight Recorder (FR-78): records one line in this very JVM, then analyses the recording. No attach and
 * no flag: {@code jdk.jfr.Recording} is driven from the process. Without the {@code jdk.jfr} module (slim image) the
 * mode simply reports that nothing is recorded.
 *
 * <p>Nothing here mentions a {@code jdk.jfr} type, so this class loads without the module.
 */
public final class PerfProfiler {

    /** A recording in progress. */
    public interface Recording extends AutoCloseable {

        /**
         * Stops the recording, writes it into {@code dir}, analyses it and applies the retention.
         *
         * @param keep files to keep, see {@link PerfFiles}
         */
        PerfProfile finish(Path dir, int keep) throws IOException;

        /** Drops the recording without analysing it (idempotent). */
        @Override
        void close();
    }

    /** Default sampling period of the Java threads, in milliseconds. */
    public static final int DEFAULT_PERIOD_MILLIS = 1;
    /** The Flight Recorder does not sample faster than once per millisecond. */
    static final int MIN_PERIOD_MILLIS = 1;
    static final int MAX_PERIOD_MILLIS = 1000;
    public static final String PERIOD_VARIABLE = "POWERJ_PERF_PERIOD";

    private static final boolean AVAILABLE = ModuleLayer.boot().findModule("jdk.jfr").isPresent();

    private PerfProfiler() {
    }

    public static boolean available() {
        return AVAILABLE;
    }

    /**
     * Starts recording; {@code null} if the runtime has no Flight Recorder.
     *
     * @param period sampling period of the Java threads, see {@link #period}
     */
    public static Recording start(java.time.Duration period) {
        return AVAILABLE ? JfrProfiler.start(period) : null;
    }

    /**
     * Sampling period from the session environment: {@value #PERIOD_VARIABLE} in whole milliseconds, between
     * {@value #MIN_PERIOD_MILLIS} and {@value #MAX_PERIOD_MILLIS}; unset, not a number or below the minimum gives the
     * default ({@value #DEFAULT_PERIOD_MILLIS} ms). Read at every line, so {@code env --set} takes effect at once.
     */
    public static java.time.Duration period(Map<String, String> environment) {
        String value = environment.get(PERIOD_VARIABLE);
        long millis = DEFAULT_PERIOD_MILLIS;
        if (value != null) {
            try {
                long parsed = Long.parseLong(value.strip());
                if (parsed >= MIN_PERIOD_MILLIS) {
                    millis = Math.min(parsed, MAX_PERIOD_MILLIS);
                }
            } catch (NumberFormatException _) {
                // not a number: the default
            }
        }
        return java.time.Duration.ofMillis(millis);
    }

    /** Number of files to keep from the session environment. */
    public static int keep(Map<String, String> environment) {
        return PerfFiles.keep(environment);
    }

    /** Names of the events the analysis reads (for documentation and tests). */
    static List<String> events() {
        return AVAILABLE ? JfrProfiler.EVENTS : List.of();
    }
}
