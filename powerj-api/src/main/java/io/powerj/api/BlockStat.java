package io.powerj.api;

import java.time.Duration;
import java.util.Objects;

/**
 * Measure of a {@code { }} block evaluated in a stage, produced by {@code perf --view blocks}. Blocks called by other
 * blocks are counted in their own row and in the one of the caller.
 *
 * @param line    number of the measured line
 * @param stage   position of the stage that evaluated the block, from 1
 * @param source  text of the block, without the braces
 * @param calls   number of evaluations
 * @param total   time spent in the block
 * @param average time per call
 * @param max     longest call
 * @param first   the first call alone: class loading, reflection and JIT warm-up show up against {@code average}
 */
@Display(columns = {"stage", "source", "calls", "total", "average", "max", "first"})
public record BlockStat(int line, int stage, String source, long calls, Duration total, Duration average,
                        Duration max, Duration first) {

    public BlockStat {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(average, "average");
        Objects.requireNonNull(max, "max");
        Objects.requireNonNull(first, "first");
    }
}
