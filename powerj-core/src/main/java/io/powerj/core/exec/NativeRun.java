package io.powerj.core.exec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Metadata of the last native command (specification FR-38), accessible through {@code $last}.
 * They are never injected into the output stream.
 *
 * @param command  absolute path of the executable
 * @param args     arguments passed
 * @param pid      process identifier
 * @param exitCode exit code, or {@code null} for a graphical application launched detached (FR-39)
 * @param duration execution time (until launch for a detached application)
 */
public record NativeRun(Path command, List<String> args, long pid, Integer exitCode, Duration duration) {

    public NativeRun {
        Objects.requireNonNull(command, "command");
        args = List.copyOf(args);
        Objects.requireNonNull(duration, "duration");
    }

    /** Success: code 0, or detached application. */
    public boolean succeeded() {
        return exitCode == null || exitCode == 0;
    }
}
