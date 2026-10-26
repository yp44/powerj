package io.powerj.api.perf;

import java.util.Objects;

import io.powerj.api.Bytes;
import io.powerj.api.Display;

/**
 * What a stage allocated, per type, produced by {@code perf --view alloc} (specification FR-76, part B). The profiler
 * samples the allocations, so the sizes are estimates, good to compare types and stages.
 *
 * @param line  number of the measured line
 * @param stage position of the stage, from 1; 0 for what ran outside the stages
 * @param type  class of the objects allocated
 * @param bytes estimated size
 */
@Display(columns = {"stage", "type", "bytes"})
public record AllocStat(int line, int stage, String type, @Bytes long bytes) {

    public AllocStat {
        Objects.requireNonNull(type, "type");
    }
}
