package io.powerj.api.perf;

import java.time.Duration;
import java.util.Objects;

import io.powerj.api.Display;

/**
 * Measure of a native process, produced by {@code perf --view natives}.
 *
 * @param line     number of the measured line
 * @param command  program and arguments
 * @param pid      process identifier
 * @param wall     execution time
 * @param exitCode exit code, {@code null} for a detached application
 */
@Display(columns = {"command", "pid", "wall", "exitCode"})
public record NativeStat(int line, String command, long pid, Duration wall, Integer exitCode) {

    public NativeStat {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(wall, "wall");
    }
}
