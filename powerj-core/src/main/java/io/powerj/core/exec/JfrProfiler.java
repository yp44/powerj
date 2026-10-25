package io.powerj.core.exec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * The {@link Profiler} that records with Java Flight Recorder. Public, with a public constructor, only because the
 * {@link java.util.ServiceLoader} instantiates it.
 */
public final class JfrProfiler implements Profiler {

    /** Called by the {@link java.util.ServiceLoader}. */
    public JfrProfiler() {
    }

    @Override
    public boolean available() {
        return PerfJfr.available();
    }

    @Override
    public void warmUp(Path file) {
        Recording capture = start(file, PerfJfr.DEFAULT_SAMPLE_PERIOD, null);
        if (capture != null) {
            capture.finish(0, List.of());
            try {
                Files.deleteIfExists(file);
            } catch (IOException _) {
                // a temporary file: removed at exit
            }
        }
    }

    @Override
    public Recording start(Path file, Duration samplePeriod, String allocThrottle) {
        return JfrCapture.start(file, samplePeriod, allocThrottle);
    }
}
