package io.powerj.core.exec;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Collects the wall-clock figures of one line (perf mode, FR-76). Installed on the {@link Interpreter} for the
 * duration of the line; absent otherwise, so the cost outside perf mode is one null test per stage.
 */
public final class PerfRecorder {

    private final long start = System.nanoTime();
    private final List<PerfReport.Statement> statements = new CopyOnWriteArrayList<>();
    /** {@code false} for the recorder that only feeds the Flight Recorder events: nothing is kept. */
    private final boolean retain;

    public PerfRecorder() {
        this(true);
    }

    private PerfRecorder(boolean retain) {
        this.retain = retain;
    }

    /** A recorder that keeps nothing, used to count objects while a Flight Recording asks for stage events. */
    static PerfRecorder profiling() {
        return new PerfRecorder(false);
    }

    /** Figures of one statement being executed. */
    final class StatementTimer {
        private final long begin = System.nanoTime();
        private final List<StageTimer> stages = new ArrayList<>();

        /** Adds a stage; call before the pipeline starts. */
        StageTimer stage(PerfPlan.Stage plan) {
            var stage = new StageTimer(plan);
            stages.add(stage);
            return stage;
        }

        void finish(String text, boolean succeeded) {
            long end = System.nanoTime();
            if (!retain) {
                return;
            }
            statements.add(new PerfReport.Statement(text, Duration.ofNanos(end - begin), succeeded,
                    stages.stream().map(StageTimer::report).toList()));
        }
    }

    /** Figures of one stage; updated from the stage's own thread. */
    static final class StageTimer {
        private final PerfPlan.Stage plan;
        private volatile long begin;
        private volatile long end;
        private volatile long first;
        private final AtomicLong in = new AtomicLong();
        private final AtomicLong out = new AtomicLong();
        private volatile boolean reads;
        private volatile Profiling.Span span = (_, _) -> { };

        StageTimer(PerfPlan.Stage plan) {
            this.plan = plan;
        }

        void started() {
            begin = System.nanoTime();
            span = Profiling.stage(plan.text().split(" ", 2)[0], plan.kind().name().toLowerCase(java.util.Locale.ROOT));
        }

        void ended() {
            end = System.nanoTime();
            span.end(reads ? in.get() : -1, out.get());
        }

        /** Counts the objects read from {@code input}. */
        Source counting(Source input) {
            if (input == null) {
                return null;
            }
            reads = true;
            return new Source() {
                @Override
                public Object next() throws InterruptedException {
                    Object value = input.next();
                    if (value != Source.END) {
                        in.incrementAndGet();
                    }
                    return value;
                }

                @Override
                public void abort() {
                    input.abort();
                }
            };
        }

        /** Counts the values sent to {@code output}. */
        Consumer<Object> counting(Consumer<Object> output) {
            return value -> {
                if (out.getAndIncrement() == 0) {
                    first = System.nanoTime();
                }
                output.accept(value);
            };
        }

        PerfReport.Stage report() {
            long finished = end == 0 ? System.nanoTime() : end;
            return new PerfReport.Stage(plan.text(), plan.kind(), Duration.ofNanos(Math.max(0, finished - begin)),
                    first == 0 ? null : Duration.ofNanos(Math.max(0, first - begin)),
                    reads ? in.get() : -1, out.get());
        }
    }

    StatementTimer statement() {
        return new StatementTimer();
    }

    /** The figures collected so far; the total runs up to this call. */
    public PerfReport report() {
        return new PerfReport(Duration.ofNanos(System.nanoTime() - start), statements);
    }
}
