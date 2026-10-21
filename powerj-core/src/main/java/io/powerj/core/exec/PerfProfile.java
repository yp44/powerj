package io.powerj.core.exec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import io.powerj.api.Display;

/**
 * What the Flight Recorder saw while one line ran in perf mode (FR-78): produced by {@link PerfProfiler}, shown under
 * the report and returned, as objects, by the {@code perf} command.
 *
 * @param file       the recording, or {@code null} if it was deleted after the analysis
 * @param wall       duration of the line
 * @param cpu        CPU time of the Java threads: samples × sampling period (a statistical estimate)
 * @param samples    execution samples taken; below {@link #LOW_SAMPLES} the figures are only indicative
 * @param allocated  bytes allocated (sampled estimate)
 * @param waited     time the threads of the stages spent parked, sleeping, waiting for a monitor or in file I/O;
 *                   the waiting of the other threads (the shell's own thread parked while the stages run, file-walk
 *                   workers…) is in {@code otherWaits}, not here, because it overlaps the stages
 * @param period     sampling period the CPU figures come from
 */
public record PerfProfile(Path file, Duration wall, Duration cpu, int samples, long allocated, Duration waited,
                          Gc gc, List<StageProfile> stages, Duration period, List<Wait> otherWaits) {

    /** Fewer samples than this: the figures are indicative (short command). */
    public static final int LOW_SAMPLES = 20;

    public PerfProfile {
        stages = List.copyOf(stages);
        otherWaits = List.copyOf(otherWaits);
    }

    public boolean low() {
        return samples < LOW_SAMPLES;
    }

    /**
     * @param count  collections during the line
     * @param pause  total stop-the-world pause
     * @param before heap used before the first collection, bytes ({@code -1} if none)
     * @param after  heap used after the last collection, bytes ({@code -1} if none)
     */
    public record Gc(int count, Duration pause, long before, long after) { }

    /** One stage of one statement. */
    public record StageProfile(int number, String command, String kind, Duration wall, Duration cpu, int samples,
                               long allocated, Duration waited, int pinned, List<HotMethod> hot,
                               List<AllocationSite> sites, List<Wait> waits) {
        public StageProfile {
            hot = List.copyOf(hot);
            sites = List.copyOf(sites);
            waits = List.copyOf(waits);
        }

        /** Too few samples for the percentages of its hot methods to mean much. */
        public boolean low() {
            return samples < LOW_SAMPLES;
        }
    }

    /** A Java method seen at the top of the stack in the samples of a stage; {@code line} is 0 when unknown. */
    @Display(columns = {"stage", "method", "line", "samples", "percent"})
    public record HotMethod(String stage, String method, int line, int samples, double percent) { }

    /** Where objects were allocated. */
    @Display(columns = {"stage", "site", "bytes", "percent"})
    public record AllocationSite(String stage, String site, long bytes, double percent) { }

    /** Time a stage spent not running, by cause ({@code park}, {@code monitor}, {@code file}). */
    @Display(columns = {"stage", "cause", "duration", "count"})
    public record Wait(String stage, String cause, Duration duration, int count) { }

    /** Per-stage totals, for {@code perf --stages}. */
    @Display(columns = {"number", "command", "kind", "wall", "cpu", "samples", "allocated", "waited"})
    public record StageRow(int number, String command, String kind, Duration wall, Duration cpu, int samples,
                           long allocated, Duration waited) { }

    /** Collections, for {@code perf --gc}. */
    @Display(columns = {"count", "pause", "before", "after"})
    public record GcRow(int count, Duration pause, long before, long after) { }
}
