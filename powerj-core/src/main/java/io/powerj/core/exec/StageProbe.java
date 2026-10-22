package io.powerj.core.exec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.powerj.api.BlockStat;
import io.powerj.api.NativeStat;
import io.powerj.api.StageStat;

/**
 * Measures one stage of a pipeline (FR-76). The wall time of the stage is taken around its run; the time spent waiting
 * for its neighbours is read from the {@link Pipe}s that link it to them, which count it only when a call has to block,
 * so no time is read per object. What is left is the {@code active} time.
 */
final class StageProbe {

    /** Body of a stage. */
    @FunctionalInterface
    interface Body {
        boolean run() throws InterruptedException;
    }

    /** Calls of a block, in nanoseconds. */
    private static final class BlockCalls {
        long calls;
        long total;
        long max;
        long first;
    }

    private final int index;
    private final String label;
    private final boolean nativeStage;
    private Pipe input;
    private Pipe output;
    private volatile long startNanos;
    private volatile long endNanos;
    private volatile long sunk;
    private volatile long displayNanos;
    private final Map<String, BlockCalls> blocks = new LinkedHashMap<>();
    private final List<NativeRun> natives = new ArrayList<>();

    StageProbe(int index, String label, boolean nativeStage) {
        this.index = index;
        this.label = label;
        this.nativeStage = nativeStage;
    }

    int index() {
        return index;
    }

    void input(Pipe pipe) {
        this.input = pipe;
    }

    void output(Pipe pipe) {
        this.output = pipe;
    }

    /** Runs the stage with this probe bound to the thread, so that the blocks it evaluates are attributed to it. */
    boolean run(Body body) throws InterruptedException {
        startNanos = System.nanoTime();
        try {
            return java.lang.ScopedValue.where(PerfRecorder.STAGE, this).call(body::run);
        } finally {
            endNanos = System.nanoTime();
        }
    }

    /** The stage was timed from outside (a single native command): it lasted {@code nanos}. */
    void measured(long nanos) {
        endNanos = System.nanoTime();
        startNanos = endNanos - nanos;
    }

    /** Wraps the sink of the last stage: counts the objects and, if {@code display}, the time spent displaying them. */
    Consumer<Object> sink(Consumer<Object> sink, boolean display) {
        return value -> {
            if (display) {
                long start = System.nanoTime();
                try {
                    sink.accept(value);
                } finally {
                    displayNanos += System.nanoTime() - start;
                }
            } else {
                sink.accept(value);
            }
            sunk++;
        };
    }

    /** Native processes of this stage. */
    synchronized void natives(List<NativeRun> runs) {
        natives.addAll(runs);
    }

    /** One evaluation of a block. */
    synchronized void block(String source, long nanos) {
        BlockCalls calls = blocks.computeIfAbsent(source, _ -> new BlockCalls());
        if (calls.calls == 0) {
            calls.first = nanos;
        }
        calls.calls++;
        calls.total += nanos;
        calls.max = Math.max(calls.max, nanos);
    }

    /** Time spent displaying the objects of this stage (last stage). */
    long displayNanos() {
        return displayNanos;
    }

    private long wallNanos() {
        long end = endNanos;
        return end == 0 ? 0 : Math.max(0, end - startNanos);
    }

    private long starvedNanos() {
        return input == null ? 0 : input.takeBlockedNanos();
    }

    private long blockedNanos() {
        return output == null ? 0 : output.putBlockedNanos();
    }

    /** Wall time without the waits and the display. */
    long activeNanos() {
        return Math.max(0, wallNanos() - starvedNanos() - blockedNanos() - displayNanos);
    }

    StageStat stat(int line, boolean bottleneck) {
        Long in = nativeStage || input == null ? null : input.takeCount();
        Long out = nativeStage ? null : output != null ? output.putCount() : sunk;
        return new StageStat(line, index, label, in, out, Duration.ofNanos(activeNanos()),
                Duration.ofNanos(starvedNanos()), Duration.ofNanos(blockedNanos()), Duration.ofNanos(wallNanos()),
                bottleneck);
    }

    synchronized List<BlockStat> blockStats(int line) {
        List<BlockStat> stats = new ArrayList<>(blocks.size());
        blocks.forEach((source, calls) -> stats.add(new BlockStat(line, index, source, calls.calls,
                Duration.ofNanos(calls.total), Duration.ofNanos(calls.total / calls.calls),
                Duration.ofNanos(calls.max), Duration.ofNanos(calls.first))));
        return stats;
    }

    synchronized List<NativeStat> nativeStats(int line) {
        return natives.stream().map(run -> {
            String command = run.command().getFileName() + (run.args().isEmpty() ? "" : " " + String.join(" ", run.args()));
            return new NativeStat(line, command, run.pid(), run.duration(), run.exitCode());
        }).toList();
    }
}
