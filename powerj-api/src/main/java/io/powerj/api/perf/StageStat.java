package io.powerj.api.perf;

import java.time.Duration;
import java.util.Objects;

import io.powerj.api.Display;

/**
 * Measure of one stage of a pipeline, produced by {@code perf} (specification FR-76).
 *
 * @param line       number of the measured line
 * @param index      position of the stage in the line, from 1
 * @param command    the stage, as typed (shortened)
 * @param in         objects received from the previous stage, {@code null} for the first stage or a native command
 * @param out        objects sent to the next stage or to the display, {@code null} for a native command
 * @param active     wall time of the stage without the time spent waiting for its neighbours (nor displaying)
 * @param starved    time spent waiting for the previous stage
 * @param blocked    time spent waiting for the next stage (backpressure)
 * @param wall       time from the start to the end of the stage
 * @param bottleneck the stage with the largest {@code active}
 */
@Display(columns = {"index", "command", "in", "out", "active", "starved", "blocked", "bottleneck"})
public record StageStat(int line, int index, String command, Long in, Long out, Duration active, Duration starved,
                        Duration blocked, Duration wall, boolean bottleneck) {

    public StageStat {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(active, "active");
        Objects.requireNonNull(starved, "starved");
        Objects.requireNonNull(blocked, "blocked");
        Objects.requireNonNull(wall, "wall");
    }
}
