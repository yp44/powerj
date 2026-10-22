package io.powerj.api;

import java.time.Duration;
import java.util.Objects;

/**
 * What the JVM did while a line ran, produced by {@code perf --view jvm}.
 *
 * @param line          number of the measured line
 * @param gcCount       garbage collections
 * @param gcTime        time spent collecting
 * @param heapPeak      peak of the heap during the line: the sum of the peaks of the memory pools, so an upper bound
 * @param classesLoaded classes loaded
 * @param jitTime       time spent by the JIT compiler
 */
@Display(columns = {"gcCount", "gcTime", "heapPeak", "classesLoaded", "jitTime"})
public record JvmStat(int line, long gcCount, Duration gcTime, @Bytes long heapPeak, long classesLoaded,
                      Duration jitTime) {

    public JvmStat {
        Objects.requireNonNull(gcTime, "gcTime");
        Objects.requireNonNull(jitTime, "jitTime");
    }
}
