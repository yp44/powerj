package io.powerj.api.perf;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import io.powerj.api.Display;

/**
 * Everything measured while a line ran in perf mode (specification FR-76), kept by the session and read by
 * {@code perf}; {@code $perf} is the last one.
 *
 * @param line      number of the measured line, from 1 in the session
 * @param text      the line as typed
 * @param total     time from the parsing to the end of the display
 * @param cancelled the line was cancelled by Ctrl+C: the report covers what ran
 * @param phases    time per phase
 * @param stages    one measure per stage, in the order of the line
 * @param blocks    one measure per block evaluated
 * @param natives   one measure per native process
 * @param jvm       garbage collections, heap, classes, JIT
 * @param hot       methods found running, per stage (profiler; empty without a recording)
 * @param gc        garbage collections of the line (profiler)
 * @param alloc     allocations per stage and type (profiler)
 * @param recording Java Flight Recorder file of the line, to open in JDK Mission Control, or {@code null} when the
 *                  runtime has no profiler; deleted when the report is no longer kept
 */
@Display(columns = {"line", "text", "total"})
public record PerfReport(int line, String text, Duration total, boolean cancelled, List<PhaseStat> phases,
                         List<StageStat> stages, List<BlockStat> blocks, List<NativeStat> natives, JvmStat jvm,
                         List<HotMethod> hot, List<GcStat> gc, List<AllocStat> alloc, Path recording) {

    public PerfReport {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(total, "total");
        phases = List.copyOf(phases);
        stages = List.copyOf(stages);
        blocks = List.copyOf(blocks);
        natives = List.copyOf(natives);
        Objects.requireNonNull(jvm, "jvm");
        hot = List.copyOf(hot);
        gc = List.copyOf(gc);
        alloc = List.copyOf(alloc);
    }

    /** The summary of the line. */
    public LineStat summary() {
        String bottleneck = stages.stream().filter(StageStat::bottleneck).map(StageStat::command).findFirst()
                .orElse("");
        return new LineStat(line, text, total, bottleneck, cancelled);
    }
}
