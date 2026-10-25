package io.powerj.core.exec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The profiler of the perf mode (FR-76, part B): records what a line does and reads it back. The one there is records
 * with Java Flight Recorder; it is found with a {@link ServiceLoader}, so that a runtime without the module
 * {@code jdk.jfr} still works (the lines are then measured with the counters only) and nothing else in the shell
 * names a type of that module.
 * <p>
 * Public only because the {@link ServiceLoader} requires it of a service type; it is not an extension point, and goes
 * out of the exported API with the qualified exports of the review's phase 3.
 */
@SuppressWarnings("exports") // the recording names package-private types; none of this is meant for a client
public interface Profiler {

    /** Is this profiler usable in the running JVM? */
    boolean available();

    /** The first recording of the JVM initializes the recorder, and the first read loads the parser: pay it once. */
    void warmUp(Path file);

    /**
     * Starts recording into {@code file}, sampling the execution every {@code samplePeriod} and the allocations at
     * most at {@code allocThrottle} ({@code 100/s}, {@code off}; {@code null}: the profiler's default).
     *
     * @return the recording, or {@code null} if the profiler cannot start (the line is measured without it)
     */
    Recording start(Path file, Duration samplePeriod, String allocThrottle);

    /** A recording in progress, for one line. */
    interface Recording {

        /** The file the recording is written to, kept with the report to open in JDK Mission Control. */
        Path file();

        /** Stops the recording and reads it. */
        JfrData finish(int line, List<StageProbe> stages);
    }

    /** The profiler of this runtime, if there is a usable one. */
    static Optional<Profiler> discover() {
        try {
            return ServiceLoader.load(Profiler.class).stream().map(ServiceLoader.Provider::get)
                    .filter(Profiler::available).findFirst();
        } catch (ServiceConfigurationError | LinkageError e) {
            Logger.getLogger(Profiler.class.getName())
                    .log(Level.WARNING, "No profiler: the lines are measured without it", e);
            return Optional.empty();
        }
    }
}
