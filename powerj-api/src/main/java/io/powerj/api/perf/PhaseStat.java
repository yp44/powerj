package io.powerj.api.perf;

import java.time.Duration;
import java.util.Objects;

import io.powerj.api.Display;

/**
 * Time spent in a phase of a line, produced by {@code perf --view phases}: {@code parse}, {@code resolve} (command
 * lookup, arguments and sub-expressions), {@code execute} and {@code display}.
 *
 * @param line number of the measured line
 * @param name phase
 * @param time time spent
 */
@Display(columns = {"name", "time"})
public record PhaseStat(int line, String name, Duration time) {

    public PhaseStat {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(time, "time");
    }
}
