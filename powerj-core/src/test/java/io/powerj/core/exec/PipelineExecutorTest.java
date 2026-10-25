package io.powerj.core.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.lang.Ast;
import io.powerj.core.lang.Parser;
import io.powerj.core.lang.Syntax;

/** The executor on its own, without the interpreter that normally builds and drives it. */
@Timeout(30)
class PipelineExecutorTest {

    @TempDir
    Path tmp;

    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Builtin> builtins = new LinkedHashMap<>();
    private final List<Object> values = Collections.synchronizedList(new ArrayList<>());
    private final RecordingObserver observer = new RecordingObserver();
    private ErrorReporter reporter;
    private PipelineExecutor executor;

    /** Writes down what the executor tells it, and counts the values that reach the sink of the last stage. */
    private static final class RecordingObserver implements ExecutionObserver, ExecutionObserver.PipelineObserver {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final List<String> labels = Collections.synchronizedList(new ArrayList<>());
        final List<Integer> positions = Collections.synchronizedList(new ArrayList<>());
        final List<Boolean> nativeStages = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger sunk = new AtomicInteger();

        @Override
        public PipelineObserver pipelineEntered() {
            events.add("entered");
            return this;
        }

        @Override
        public void displayed(long nanos) {
            events.add("displayed");
        }

        @Override
        public void resolved(long nanos) {
            events.add("resolved");
        }

        @Override
        public StageObserver stage(int position, String label, boolean nativeStage) {
            positions.add(position);
            labels.add(label);
            nativeStages.add(nativeStage);
            StageObserver plain = new StageObserver.Unobserved(position);
            return new StageObserver() {
                @Override
                public String threadName() {
                    return plain.threadName();
                }

                @Override
                public Consumer<Object> sink(Consumer<Object> sink, boolean display) {
                    return value -> {
                        sunk.incrementAndGet();
                        sink.accept(value);
                    };
                }

                @Override
                public boolean run(Body body) throws InterruptedException {
                    return plain.run(body);
                }

                @Override
                public void measured(long nanos) {
                    plain.measured(nanos);
                }

                @Override
                public void natives(List<NativeRun> runs) {
                    plain.natives(runs);
                }
            };
        }

        @Override
        public Pipe pipe(StageObserver writer, StageObserver reader) {
            events.add("pipe");
            return new Pipe();
        }

        @Override
        public void exited() {
            events.add("exited");
        }
    }

    @BeforeEach
    void setUp() {
        var session = new Session(tmp, tmp, Map.of());
        var io = new ShellIo(new PrintWriter(new StringWriter(), true), errors::add, false);
        var registry = CmdletRegistry.of(FakeCmdlets.all());
        builtins.put("three", (_, _) -> List.of("a", "b", "c"));
        builtins.put("boom", (_, _) -> {
            throw new PjException("boom");
        });
        var resolution = new CommandResolution(builtins, registry, new CommandResolver());
        reporter = new ErrorReporter(session, io);
        var evaluator = new Evaluator(session, _ -> {
            throw new UnsupportedOperationException("no subexpression in these tests");
        }, () -> new Syntax(_ -> false, _ -> false));
        executor = new PipelineExecutor(session, io, resolution, new NativeRunner(), evaluator, reporter,
                () -> observer);
    }

    private static Ast.Pipeline pipeline(String line) {
        return Parser.parse(line, _ -> false).steps().getFirst().statement().pipeline();
    }

    private boolean run(String line) throws InterruptedException {
        values.clear();
        return executor.execute(pipeline(line), values::add, false, Redirections.NONE);
    }

    @Test
    void theValuesOfTheLastStageReachTheSink() throws Exception {
        assertThat(run("items -n 3 | filter { $_.size > 15 }")).isTrue();

        assertThat(values).extracting(v -> ((FakeCmdlets.Item) v).name()).containsExactly("item2", "item3");
    }

    @Test
    void aBuiltinCommandCanHeadAPipeline() throws Exception {
        assertThat(run("three | count")).isTrue();

        assertThat(values).containsExactly(3L);
    }

    @Test
    void aBuiltinCommandCannotReadObjects() {
        assertThatThrownBy(() -> run("items | three")).isInstanceOf(PjException.class)
                .hasMessage("\"three\" does not read pipeline objects");
    }

    @Test
    void anUnknownCommandStopsTheLineBeforeAnythingRuns() {
        assertThatThrownBy(() -> run("items | nothing-of-that-name")).isInstanceOf(PjException.class)
                .hasMessage("unknown command: nothing-of-that-name");
        assertThat(values).isEmpty();
    }

    @Test
    void standardInputFeedsTheFirstStage() throws Exception {
        executor.useStandardInput(List.of("x", "y").iterator());

        assertThat(run("count")).isTrue();

        assertThat(values).containsExactly(2L);
    }

    @Test
    void aBlockingErrorInAnEarlierStageFailsThePipelineAndIsRemembered() throws Exception {
        assertThat(run("boom | count")).isFalse();

        assertThat(errors).containsExactly("boom");
        assertThat(reporter.hadBlocking()).isTrue();
    }

    @Test
    void aBlockingErrorInTheLastStageIsLeftToTheCaller() {
        // The last stage runs in the calling thread: its error propagates, and the statement reports it.
        assertThatThrownBy(() -> run("boom")).isInstanceOf(PjException.class).hasMessage("boom");
        assertThat(reporter.hadBlocking()).isFalse();
    }

    @Test
    void onlyNativeCommandsRunInTheBackground() {
        assertThatThrownBy(() -> executor.background(pipeline("items"), Redirections.NONE))
                .isInstanceOf(PjException.class).hasMessageStartingWith("& only applies to native commands");
    }

    @Test
    void theObserverHearsOfEveryStageOfThePipeline() throws Exception {
        run("items -n 3 | filter { $_.size > 15 } | count");

        assertThat(observer.positions).containsExactly(1, 2, 3);
        assertThat(observer.labels).containsExactly("items -n 3", "filter {…}", "count");
        assertThat(observer.nativeStages).containsOnly(false);
        assertThat(observer.events).containsExactly("entered", "resolved", "pipe", "pipe", "exited");
    }

    @Test
    void theValuesOfTheLastStageGoThroughTheObserver() throws Exception {
        run("items -n 3 | filter { $_.size > 15 }");

        assertThat(observer.sunk).hasValue(2);
        assertThat(values).hasSize(2);
    }

    @Test
    void theObserverIsToldTheEndOfAPipelineThatCouldNotBePlanned() {
        assertThatThrownBy(() -> run("items | nothing-of-that-name")).isInstanceOf(PjException.class);

        assertThat(observer.events).containsExactly("entered", "exited");
    }

    @Test
    void theObserverIsToldTheEndOfAPipelineThatFailed() {
        assertThatThrownBy(() -> run("boom")).isInstanceOf(PjException.class);

        assertThat(observer.events).endsWith("exited");
    }
}
