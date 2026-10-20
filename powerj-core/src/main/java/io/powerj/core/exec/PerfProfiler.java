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

    private static final boolean AVAILABLE = ModuleLayer.boot().findModule("jdk.jfr").isPresent();

    private PerfProfiler() {
    }

    public static boolean available() {
        return AVAILABLE;
    }

    /** Starts recording; {@code null} if the runtime has no Flight Recorder. */
    public static Recording start() {
        return AVAILABLE ? JfrProfiler.start() : null;
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
