package io.powerj.api;

import java.time.Duration;
import java.util.Objects;

/**
 * A garbage collection that happened while a line ran, produced by {@code perf --view gc} (specification FR-76,
 * part B).
 *
 * @param line  number of the measured line
 * @param name  collector and generation ({@code G1Young}…)
 * @param cause why it ran ({@code G1 Evacuation Pause}, {@code System.gc()}…)
 * @param pause time the application was stopped
 */
@Display(columns = {"name", "cause", "pause"})
public record GcStat(int line, String name, String cause, Duration pause) {

    public GcStat {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(pause, "pause");
    }
}
