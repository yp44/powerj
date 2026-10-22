package io.powerj.api;

import java.time.Duration;
import java.util.Objects;

/**
 * Summary of a measured line, produced by {@code perf --view lines}.
 *
 * @param line       number of the measured line
 * @param text       the line as typed
 * @param total      time from the parsing to the end of the display
 * @param bottleneck the stage with the largest {@code active}, empty if none
 * @param cancelled  the line was cancelled by Ctrl+C
 */
@Display(columns = {"line", "text", "total", "bottleneck", "cancelled"})
public record LineStat(int line, String text, Duration total, String bottleneck, boolean cancelled) {

    public LineStat {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(bottleneck, "bottleneck");
    }
}
