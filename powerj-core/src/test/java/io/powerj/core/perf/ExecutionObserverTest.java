package io.powerj.core.perf;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import io.powerj.core.java.PreviewGuard;
import io.powerj.core.runtime.ExecutionObserver;
import io.powerj.core.runtime.ExecutionObserver.PipelineObserver;
import io.powerj.core.runtime.ExecutionObserver.StageObserver;
import io.powerj.core.runtime.MeasuredPipe;
import io.powerj.core.runtime.Pipe;

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
}
