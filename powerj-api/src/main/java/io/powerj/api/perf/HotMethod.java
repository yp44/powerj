package io.powerj.api.perf;

import java.util.Objects;

import io.powerj.api.Display;

/**
 * A method that the profiler found running in a stage, produced by {@code perf --view hot} (specification FR-76, part B).
 * Only the method on top of the stack is counted, so it is where the time is spent, not who called it.
 *
 * @param line    number of the measured line
 * @param stage   position of the stage, from 1; 0 for what ran outside the stages (parsing, resolution, display)
 * @param method  class and method
 * @param samples times the profiler found the method running
 * @param percent share of the samples of the stage, from 0 to 100
 */
@Display(columns = {"stage", "method", "samples", "percent"})
public record HotMethod(int line, int stage, String method, long samples, double percent) {

    public HotMethod {
        Objects.requireNonNull(method, "method");
    }
}
