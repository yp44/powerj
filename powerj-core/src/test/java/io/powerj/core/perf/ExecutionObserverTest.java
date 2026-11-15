package io.powerj.core.perf;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import io.powerj.api.perf.PhaseStat;
import io.powerj.api.perf.StageStat;
import io.powerj.core.java.PreviewGuard;
import io.powerj.core.runtime.ExecutionObserver;
import io.powerj.core.runtime.ExecutionObserver.PipelineObserver;
import io.powerj.core.runtime.ExecutionObserver.StageObserver;
import io.powerj.core.runtime.MeasuredPipe;
import io.powerj.core.runtime.Pipe;
import io.powerj.core.support.Waits;

/** The observer that measures nothing, and the one that does (perf mode, FR-76). */
class ExecutionObserverTest {

    @Test
    void nobodyMeasuringAddsNothingToTheStages() throws Exception {
        StageObserver stage = PipelineObserver.NONE.stage(3, "items", false);
        Consumer<Object> sink = _ -> { };

        assertThat(stage.sink(sink, true)).isSameAs(sink);
        assertThat(stage.run(() -> true)).isTrue();
        assertThat(stage.run(() -> false)).isFalse();
        stage.measured(10);
        stage.natives(List.of());
    }

    @Test
    void anUnmeasuredStageStillHasTheNameOfItsPosition() {
        assertThat(PipelineObserver.NONE.stage(1, "a", false).threadName()).isEqualTo(ExecutionObserver.threadName(1));
        assertThat(PipelineObserver.NONE.stage(4, "b", true).threadName()).isEqualTo(ExecutionObserver.threadName(4));
    }

    @Test
    void anUnmeasuredPipeIsAPlainOne() {
        var writer = PipelineObserver.NONE.stage(1, "a", false);
        var reader = PipelineObserver.NONE.stage(2, "b", false);

        assertThat(PipelineObserver.NONE.pipe(writer, reader)).isNotInstanceOf(MeasuredPipe.class);
    }

    @Test
    void theRecorderMeasuresTheOutermostPipelineOnly() {
        var recorder = new PerfRecorder("line", System.nanoTime(), 0);

        PipelineObserver outer = recorder.pipelineEntered();
        PipelineObserver inner = recorder.pipelineEntered(); // a pipeline of an argument, while the outer one is open

        assertThat(outer).isSameAs(recorder);
        assertThat(inner).isSameAs(PipelineObserver.NONE);

        outer.exited();
        assertThat(recorder.pipelineEntered()).isSameAs(recorder); // the next statement of the line is measured too
    }

    @Test
    void thePreviewIsNeverMeasured() throws Exception {
        var recorder = new PerfRecorder("line", System.nanoTime(), 0);

        PipelineObserver observed = new PreviewGuard().call(recorder::pipelineEntered);

        assertThat(observed).isSameAs(PipelineObserver.NONE);
        assertThat(recorder.pipelineEntered()).isSameAs(recorder); // and it did not take the place of the real one
    }

    @Test
    void theStagesOfTheRecorderAreNumberedAcrossTheStatementsOfTheLine() {
        var recorder = new PerfRecorder("line", System.nanoTime(), 0);
        PipelineObserver first = recorder.pipelineEntered();
        StageObserver a = first.stage(1, "a", false);
        StageObserver b = first.stage(2, "b", false);
        first.exited();
        StageObserver c = recorder.pipelineEntered().stage(1, "c", false); // the second statement: position 1 again

        assertThat(a.threadName()).isEqualTo(ExecutionObserver.threadName(1));
        assertThat(b.threadName()).isEqualTo(ExecutionObserver.threadName(2));
        assertThat(c.threadName()).isEqualTo(ExecutionObserver.threadName(3)); // the profiler reads the stage back from it
    }

    @Test
    void theRecorderLinksItsStagesWithAPipeThatCounts() throws Exception {
        var recorder = new PerfRecorder("line", System.nanoTime(), 0);
        PipelineObserver observed = recorder.pipelineEntered();
        StageObserver writer = observed.stage(1, "a", false);
        StageObserver reader = observed.stage(2, "b", false);

        Pipe pipe = observed.pipe(writer, reader);
        pipe.put("x");
        pipe.next();

        var stats = recorder.finish(1, false).stages();
        assertThat(pipe).isInstanceOf(MeasuredPipe.class);
        assertThat(stats).extracting(stat -> stat.index()).containsExactly(1, 2);
        assertThat(stats.getFirst().out()).isEqualTo(1L);
        assertThat(stats.getLast().in()).isEqualTo(1L);
    }

    @Test
    void theRecorderCountsWhatIsDisplayedAndResolved() {
        var recorder = new PerfRecorder("line", System.nanoTime(), 0);

        recorder.resolved(5_000_000);
        recorder.displayed(7_000_000);

        assertThat(recorder.finish(1, false).phases()).anySatisfy(phase -> {
            assertThat(phase.name()).isEqualTo("resolve");
            assertThat(phase.time().toNanos()).isEqualTo(5_000_000);
        }).anySatisfy(phase -> {
            assertThat(phase.name()).isEqualTo("display");
            assertThat(phase.time().toNanos()).isEqualTo(7_000_000);
        });
    }

    // --- what the report derives from the counters, checked with the times given instead of the ones the machine measures ---

    @Test
    void theStageThatWorkedLongestIsTheBottleneck() {
        var recorder = new PerfRecorder("line", System.nanoTime(), 0);
        PipelineObserver observed = recorder.pipelineEntered();
        // `measured` is how a native stage reports its time: the wall time of the stage is the one given
        long[] millis = {5, 50, 20};
        for (int i = 0; i < millis.length; i++) {
            observed.stage(i + 1, "s" + i, false).measured(millis[i] * 1_000_000);
        }

        var stats = recorder.finish(1, false).stages();

        assertThat(stats).extracting(stat -> stat.wall().toMillis()).containsExactly(5L, 50L, 20L);
        assertThat(stats).extracting(StageStat::bottleneck).containsExactly(false, true, false);
    }

    @Test
    void theTimeSpentWaitingForTheOtherStagesIsNotActiveTime() throws Exception {
        var recorder = new PerfRecorder("line", System.nanoTime(), 0);
        PipelineObserver observed = recorder.pipelineEntered();
        StageObserver writer = observed.stage(1, "a", false);
        StageObserver reader = observed.stage(2, "b", false);
        Pipe pipe = observed.pipe(writer, reader);
        Thread waiting = Thread.ofVirtual().start(() -> {
            try {
                pipe.next();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Waits.until("the reader waits for an object", () -> waiting.getState() == Thread.State.WAITING
                || waiting.getState() == Thread.State.TIMED_WAITING);
        pipe.put("x");
        waiting.join();
        writer.measured(10_000_000_000L); // ten seconds: far more than the wait that was just measured
        reader.measured(10_000_000_000L);

        var stats = recorder.finish(1, false).stages();

        assertThat(stats.get(1).starved()).isPositive();
        assertThat(stats.get(1).active()).isEqualTo(stats.get(1).wall().minus(stats.get(1).starved()));
        assertThat(stats.get(0).blocked()).isZero(); // the queue never filled up
        assertThat(stats.get(0).active()).isEqualTo(stats.get(0).wall());
    }

    @Test
    void theParsingTheResolutionTheDisplayAndTheExecutionAreTheWholeLine() {
        long begin = System.nanoTime() - 1_000_000_000L; // the line started a second ago
        var recorder = new PerfRecorder("line", begin, 100_000_000L);
        recorder.resolved(200_000_000L);
        recorder.displayed(300_000_000L);

        var report = recorder.finish(1, false);

        assertThat(report.total()).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
        assertThat(report.phases()).extracting(PhaseStat::name).containsExactly("parse", "resolve", "execute", "display");
        assertThat(report.phases().stream().map(PhaseStat::time).reduce(Duration.ZERO, Duration::plus))
                .isEqualTo(report.total());
        assertThat(report.phases().get(2).time()).isEqualTo(report.total().minus(Duration.ofMillis(600)));
    }

    @Test
    void theExecutionIsNeverNegative() {
        var recorder = new PerfRecorder("line", System.nanoTime(), 5_000_000_000L); // a parse longer than the line

        assertThat(recorder.finish(1, false).phases().get(2).time()).isZero();
    }
}
