package io.powerj.core.exec;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import io.powerj.api.BlockStat;
import io.powerj.api.JvmStat;
import io.powerj.api.NativeStat;
import io.powerj.api.PerfReport;
import io.powerj.api.PhaseStat;
import io.powerj.api.StageStat;

/**
 * Measures one line in perf mode (FR-76): the stages and their blocks, the phases, and what the JVM did meanwhile.
 * Only the outermost pipeline of the line is measured, the pipelines of arguments and sub-expressions count in
 * {@code resolve}.
 */
final class PerfRecorder {

    /** Probe of the stage the current thread runs, seen by the blocks it evaluates. */
    static final ScopedValue<StageProbe> STAGE = ScopedValue.newInstance();

    /** What the JVM counted up to a moment. */
    private record JvmCounters(long gcCount, long gcMillis, long classes, long jitMillis) {

        static JvmCounters now() {
            long count = 0;
            long millis = 0;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                count += Math.max(0, gc.getCollectionCount());
                millis += Math.max(0, gc.getCollectionTime());
            }
            CompilationMXBean jit = ManagementFactory.getCompilationMXBean();
            ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();
            return new JvmCounters(count, millis, classes.getTotalLoadedClassCount(),
                    jit != null && jit.isCompilationTimeMonitoringSupported() ? jit.getTotalCompilationTime() : 0);
        }
    }

    private final String text;
    private final long beginNanos;
    private final long parseNanos;
    private final JvmCounters jvmBefore;
    private final AtomicBoolean pipelineOpen = new AtomicBoolean();
    private final List<StageProbe> stages = new ArrayList<>();
    private volatile long resolveNanos;
    private volatile long closeNanos;
    private long endNanos;
    private JvmCounters jvmAfter;
    private long heapPeak;

    /**
     * @param beginNanos {@code System.nanoTime()} when the line started to be parsed
     * @param parseNanos duration of the parsing
     */
    PerfRecorder(String text, long beginNanos, long parseNanos) {
        this.text = text;
        this.beginNanos = beginNanos;
        this.parseNanos = parseNanos;
        resetHeapPeaks();
        this.jvmBefore = JvmCounters.now();
    }

    /** First use of the management beans loads many classes: done when the mode is switched on, not in the first line. */
    static void warmUp() {
        resetHeapPeaks();
        JvmCounters.now();
    }

    private static void resetHeapPeaks() {
        ManagementFactory.getMemoryPoolMXBeans().stream().filter(pool -> pool.getType() == MemoryType.HEAP)
                .forEach(pool -> pool.resetPeakUsage());
    }

    /** {@code true} for the outermost pipeline only: it is the one to measure; it must then be {@link #exitPipeline}d. */
    boolean enterPipeline() {
        return pipelineOpen.compareAndSet(false, true);
    }

    void exitPipeline() {
        pipelineOpen.set(false);
    }

    void addResolve(long nanos) {
        resolveNanos += nanos;
    }

    void addDisplay(long nanos) {
        closeNanos += nanos;
    }

    synchronized StageProbe newStage(String label, boolean nativeStage) {
        var probe = new StageProbe(stages.size() + 1, label, nativeStage);
        stages.add(probe);
        return probe;
    }

    /**
     * The line is over: takes the time and what the JVM did, before anything else (the profiler's own work, reading its
     * recording, must not count).
     */
    void stop() {
        if (jvmAfter == null) {
            endNanos = System.nanoTime();
            jvmAfter = JvmCounters.now();
            heapPeak = ManagementFactory.getMemoryPoolMXBeans().stream().filter(pool -> pool.getType() == MemoryType.HEAP)
                    .mapToLong(pool -> pool.getPeakUsage().getUsed()).sum();
        }
    }

    synchronized List<StageProbe> probes() {
        return List.copyOf(stages);
    }

    /** The report of the line, whether it ended or was cancelled; {@link #stop} is called if it was not. */
    PerfReport finish(int line, boolean cancelled) {
        return finish(line, cancelled, JfrData.EMPTY, null);
    }

    /** @param recording file of the profiler's recording, or {@code null} */
    PerfReport finish(int line, boolean cancelled, JfrData profile, Path recording) {
        stop();
        long total = endNanos - beginNanos;
        List<StageProbe> probes = probes();
        long display = closeNanos + probes.stream().mapToLong(StageProbe::displayNanos).sum();
        long execute = Math.max(0, total - parseNanos - resolveNanos - display);
        List<PhaseStat> phases = List.of(
                new PhaseStat(line, "parse", Duration.ofNanos(parseNanos)),
                new PhaseStat(line, "resolve", Duration.ofNanos(resolveNanos)),
                new PhaseStat(line, "execute", Duration.ofNanos(execute)),
                new PhaseStat(line, "display", Duration.ofNanos(display)));

        StageProbe slowest = probes.size() > 1
                ? probes.stream().max(Comparator.comparingLong(StageProbe::activeNanos)).orElse(null) : null;
        List<StageStat> stats = new ArrayList<>();
        List<BlockStat> blocks = new ArrayList<>();
        List<NativeStat> natives = new ArrayList<>();
        for (StageProbe probe : probes) {
            stats.add(probe.stat(line, probe == slowest));
            blocks.addAll(probe.blockStats(line));
            natives.addAll(probe.nativeStats(line));
        }
        return new PerfReport(line, text, Duration.ofNanos(total), cancelled, phases, stats, blocks, natives,
                jvm(line), profile.hot(), profile.gc(), profile.alloc(), recording);
    }

    private JvmStat jvm(int line) {
        return new JvmStat(line, jvmAfter.gcCount() - jvmBefore.gcCount(),
                Duration.ofMillis(jvmAfter.gcMillis() - jvmBefore.gcMillis()), heapPeak,
                jvmAfter.classes() - jvmBefore.classes(),
                Duration.ofMillis(jvmAfter.jitMillis() - jvmBefore.jitMillis()));
    }
}
