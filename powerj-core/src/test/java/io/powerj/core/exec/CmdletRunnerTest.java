package io.powerj.core.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.ScriptBlock;

/** How a cmdlet is run: option binding, the begin/process/end contract, errors, the input (review C-30). */
class CmdletRunnerTest {

    @TempDir
    Path tmp;

    /** Records what the runner does to a cmdlet and lets a test make it misbehave. */
    @CmdletInfo(name = "probe", category = "Test", summary = "Probe")
    static final class Probe implements Cmdlet<Probe.Params, Object, String> {

        record Params(@Option(position = 0, description = "word") String word, @Option(shortName = 'f') boolean fail) { }

        final List<String> calls = new ArrayList<>();
        Consumer<CmdletContext<String>> onBegin = _ -> { };
        java.util.function.BiConsumer<Object, CmdletContext<String>> onProcess = (_, _) -> { };
        Consumer<CmdletContext<String>> onEnd = _ -> { };
        CmdletContext<String> context;

        @Override
        public void begin(Params p, CmdletContext<String> context) {
            this.context = context;
            calls.add("begin " + p.word());
            onBegin.accept(context);
        }

        @Override
        public void process(Params p, Object input, CmdletContext<String> context) {
            calls.add("process " + input);
            onProcess.accept(input, context);
        }

        @Override
        public void end(Params p, CmdletContext<String> context) {
            calls.add("end");
            onEnd.accept(context);
        }
    }

    /** A source that records being aborted. */
    private static final class RecordingSource implements Source {

        private final Iterator<?> items;
        boolean aborted;

        RecordingSource(Object... items) {
            this.items = List.of(items).iterator();
        }

        @Override
        public Object next() {
            return items.hasNext() ? items.next() : END;
        }

        @Override
        public void abort() {
            aborted = true;
        }
    }

    private final List<String> errors = new ArrayList<>();
    private final List<Object> output = new ArrayList<>();

    private boolean run(Probe probe, Source input, Object... args) throws Exception {
        var registered = CmdletRegistry.of(List.of(probe)).find("probe").orElseThrow();
        var session = new Session(tmp, tmp, Map.of("HOME", tmp.toString()));
        ScriptBlock compiled = new ScriptBlock() {
            @Override
            public Object invoke(Object current) {
                return current;
            }

            @Override
            public String source() {
                return "$_";
            }
        };
        return CmdletRunner.run(registered, new ArrayList<>(List.of(args)), session,
                new CmdletRunner.Connections(input, output::add, errors::add), _ -> compiled);
    }

    @Test
    void runsBeginThenProcessForEachInputThenEnd() throws Exception {
        Probe probe = new Probe();

        boolean succeeded = run(probe, new RecordingSource("a", "b"), "word");

        assertThat(probe.calls).containsExactly("begin word", "process a", "process b", "end");
        assertThat(succeeded).isTrue();
        assertThat(errors).isEmpty();
    }

    @Test
    void withoutInputOnlyBeginAndEndAreCalled() throws Exception {
        Probe probe = new Probe();

        run(probe, null, "w");

        assertThat(probe.calls).containsExactly("begin w", "end");
        assertThat(probe.context.hasInput()).isFalse();
    }

    @Test
    void theContextTellsWhetherAnInputIsConnected() throws Exception {
        Probe probe = new Probe();

        run(probe, new RecordingSource(), "w");

        assertThat(probe.context.hasInput()).isTrue();
    }

    @Test
    void emittedObjectsGoToTheSink() throws Exception {
        Probe probe = new Probe();
        probe.onBegin = context -> {
            context.emit("one");
            context.emit("two");
        };

        run(probe, null, "w");

        assertThat(output).containsExactly("one", "two");
    }

    @Test
    void anObjectOfTheWrongTypeIsSkippedWithAnError() throws Exception {
        var registered = CmdletRegistry.of(List.of(new Typed())).find("typed").orElseThrow();
        var session = new Session(tmp, tmp, Map.of());
        List<String> seen = new ArrayList<>();
        Typed.seen = seen;

        boolean succeeded = CmdletRunner.run(registered, new ArrayList<>(), session,
                new CmdletRunner.Connections(new RecordingSource("text", 42, "more"), output::add, errors::add),
                _ -> null);

        assertThat(seen).containsExactly("text", "more");
        assertThat(errors).singleElement().asString().contains("Integer").contains("String");
        assertThat(succeeded).isFalse(); // the skip is a reported error: $? is false
    }

    @CmdletInfo(name = "typed", category = "Test", summary = "Reads strings")
    static final class Typed implements Cmdlet<Typed.Params, String, String> {

        record Params() { }

        static List<String> seen;

        @Override
        public void process(Params p, String input, CmdletContext<String> context) {
            seen.add(input);
        }
    }

    @Test
    void aNonBlockingErrorIsReportedAndTheRunFails() throws Exception {
        Probe probe = new Probe();
        probe.onBegin = context -> context.error("boom");

        boolean succeeded = run(probe, null, "w");

        assertThat(errors).singleElement().asString().contains("probe").contains("boom");
        assertThat(succeeded).isFalse();
        assertThat(probe.calls).containsExactly("begin w", "end");
    }

    @Test
    void onErrorSilentKeepsTheFailureButSaysNothing() throws Exception {
        Probe probe = new Probe();
        probe.onBegin = context -> context.error("boom");

        boolean succeeded = run(probe, null, "w", "--on-error", "silent");

        assertThat(errors).isEmpty();
        assertThat(succeeded).isFalse();
    }

    @Test
    void onErrorStopTurnsTheErrorIntoABlockingOne() {
        Probe probe = new Probe();
        probe.onBegin = context -> context.error("boom");

        assertThatThrownBy(() -> run(probe, null, "w", "--on-error=stop")).isInstanceOf(PjException.class)
                .hasMessageContaining("boom");
    }

    @Test
    void onErrorContinueIsTheDefaultAndMayBeGivenExplicitly() throws Exception {
        Probe probe = new Probe();
        probe.onBegin = context -> context.error("boom");

        run(probe, null, "--on-error", "continue", "w");

        assertThat(errors).hasSize(1);
        assertThat(probe.calls).first().isEqualTo("begin w"); // --on-error is not an option of the cmdlet
    }

    @Test
    void anInvalidOnErrorValueIsRefused() {
        assertThatThrownBy(() -> run(new Probe(), null, "w", "--on-error", "explode")).isInstanceOf(PjException.class)
                .hasMessageContaining("probe");
        assertThatThrownBy(() -> run(new Probe(), null, "w", "--on-error")).isInstanceOf(PjException.class)
                .hasMessageContaining("probe");
    }

    @Test
    void anIllegalArgumentExceptionIsAUsageError() {
        Probe probe = new Probe();
        probe.onBegin = _ -> {
            throw new IllegalArgumentException("count must be positive");
        };

        assertThatThrownBy(() -> run(probe, null, "w")).isInstanceOf(PjException.class)
                .hasMessageContaining("probe").hasMessageContaining("count must be positive")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anyOtherExceptionReachesTheCaller() {
        Probe probe = new Probe();
        probe.onBegin = _ -> {
            throw new IllegalStateException("bug in the cmdlet");
        };

        assertThatThrownBy(() -> run(probe, null, "w")).isInstanceOf(IllegalStateException.class)
                .hasMessage("bug in the cmdlet");
    }

    @Test
    void stopInputStopsReadingAbortsTheSourceAndStillCallsEnd() throws Exception {
        Probe probe = new Probe();
        probe.onProcess = (_, context) -> context.stopInput();
        RecordingSource source = new RecordingSource("a", "b", "c");

        run(probe, source, "w");

        assertThat(probe.calls).containsExactly("begin w", "process a", "end");
        assertThat(source.aborted).isTrue();
    }

    @Test
    void theSessionIsSeenThroughTheContext() throws Exception {
        Probe probe = new Probe();
        List<Object> seen = new ArrayList<>();
        probe.onBegin = context -> {
            seen.add(context.currentDirectory());
            seen.add(context.environment().get("HOME"));
            seen.add(context.variable("nothing-defined-here").isPresent());
            seen.add(context.configDirectory().isPresent());
            seen.add(context.cancelled());
        };

        run(probe, null, "w");

        assertThat(seen).containsExactly(tmp.toAbsolutePath().normalize(), tmp.toString(), false, false, false);
    }

    @Test
    void emittingWhileInterruptedCancelsTheCmdlet() {
        Probe probe = new Probe();
        probe.onBegin = context -> {
            Thread.currentThread().interrupt();
            context.emit("never delivered");
        };

        try {
            assertThatThrownBy(() -> run(probe, null, "w")).isInstanceOf(CancellationException.class);
            assertThat(output).isEmpty();
        } finally {
            Thread.interrupted();
        }
    }

    // --- The state of an execution (review C-01) ---

    /** A state that records being closed in the log of the probe. */
    private static class Resource implements AutoCloseable {

        private final List<String> log;
        private final String name;
        Exception closeFailure;

        Resource(List<String> log, String name) {
            this.log = log;
            this.name = name;
        }

        @Override
        public void close() throws Exception {
            log.add("close " + name);
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    private static final class First extends Resource {
        First(List<String> log) {
            super(log, "first");
        }
    }

    private static final class Second extends Resource {
        Second(List<String> log) {
            super(log, "second");
        }
    }

    /** Not closeable: the runner just lets it go. */
    private record Counter(int[] value) { }

    private static void begin(Probe probe, Consumer<CmdletContext<String>> action) {
        probe.onBegin = action;
    }

    @Test
    void theStateIsTheSameForBeginProcessAndEnd() throws Exception {
        Probe probe = new Probe();
        List<Object> seen = new ArrayList<>();
        probe.onBegin = context -> context.state(Counter.class, () -> new Counter(new int[1]));
        probe.onProcess = (_, context) -> seen.add(context.state(Counter.class, () -> {
            throw new AssertionError("the state of begin must be found");
        }).value()[0]++);
        probe.onEnd = context -> seen.add("total " + context.state(Counter.class, () -> {
            throw new AssertionError("the state of begin must be found");
        }).value()[0]);

        run(probe, new RecordingSource("a", "b", "c"), "w");

        assertThat(seen).containsExactly(0, 1, 2, "total 3");
    }

    @Test
    void everyExecutionHasItsOwnState() throws Exception {
        Probe probe = new Probe(); // one cmdlet object, two runs with equal parameters
        List<Object> states = new ArrayList<>();
        probe.onBegin = context -> states.add(context.state(Counter.class, () -> new Counter(new int[1])));

        run(probe, null, "w");
        run(probe, null, "w");

        assertThat(states).hasSize(2).doesNotHaveDuplicates();
        assertThat(states.get(0)).isNotSameAs(states.get(1));
    }

    @Test
    void severalKindsOfStateAreIndependent() throws Exception {
        Probe probe = new Probe();
        List<Object> seen = new ArrayList<>();
        probe.onBegin = context -> {
            seen.add(context.state(First.class, () -> new First(probe.calls)));
            seen.add(context.state(Second.class, () -> new Second(probe.calls)));
            seen.add(context.state(First.class, () -> {
                throw new AssertionError("already made");
            }));
        };

        run(probe, null, "w");

        assertThat(seen.get(0)).isNotSameAs(seen.get(1)).isSameAs(seen.get(2));
    }

    @Test
    void theStateIsClosedAfterEnd() throws Exception {
        Probe probe = new Probe();
        begin(probe, context -> context.state(First.class, () -> new First(probe.calls)));

        run(probe, new RecordingSource("a"), "w");

        assertThat(probe.calls).containsExactly("begin w", "process a", "end", "close first");
    }

    @Test
    void theStatesAreClosedLastCreatedFirst() throws Exception {
        Probe probe = new Probe();
        begin(probe, context -> {
            context.state(First.class, () -> new First(probe.calls));
            context.state(Second.class, () -> new Second(probe.calls));
        });

        run(probe, null, "w");

        assertThat(probe.calls).containsExactly("begin w", "end", "close second", "close first");
    }

    /**
     * Review C-01: a cmdlet that fails in {@code process} used to leave its state behind. {@code end()} is not called
     * then, because it is where the results are emitted ({@code sort}, {@code count}…) and an error must not produce
     * any; the state is closed instead.
     */
    @Test
    void theStateIsClosedEvenWhenProcessFails() {
        Probe probe = new Probe();
        begin(probe, context -> context.state(First.class, () -> new First(probe.calls)));
        probe.onProcess = (_, _) -> {
            throw new IllegalStateException("failure in process");
        };

        assertThatThrownBy(() -> run(probe, new RecordingSource("a"), "w")).isInstanceOf(IllegalStateException.class);

        assertThat(probe.calls).containsExactly("begin w", "process a", "close first");
    }

    @Test
    void theStateIsClosedWhenBeginFails() {
        Probe probe = new Probe();
        probe.onBegin = context -> {
            context.state(First.class, () -> new First(probe.calls));
            throw new IllegalArgumentException("bad option");
        };

        assertThatThrownBy(() -> run(probe, null, "w")).isInstanceOf(PjException.class).hasMessageContaining("bad option");

        assertThat(probe.calls).containsExactly("begin w", "close first");
    }

    @Test
    void theStateIsClosedWhenTheCommandIsCancelled() {
        Probe probe = new Probe();
        begin(probe, context -> context.state(First.class, () -> new First(probe.calls)));
        probe.onProcess = (_, _) -> {
            throw new CancellationException();
        };

        assertThatThrownBy(() -> run(probe, new RecordingSource("a"), "w")).isInstanceOf(CancellationException.class);

        assertThat(probe.calls).containsExactly("begin w", "process a", "close first");
    }

    @Test
    void theStateIsClosedWhenEndFails() {
        Probe probe = new Probe();
        begin(probe, context -> context.state(First.class, () -> new First(probe.calls)));
        probe.onEnd = _ -> {
            throw new IllegalStateException("failure in end");
        };

        assertThatThrownBy(() -> run(probe, null, "w")).isInstanceOf(IllegalStateException.class);

        assertThat(probe.calls).containsExactly("begin w", "end", "close first");
    }

    @Test
    void aFailureToCloseDoesNotHideTheFailureOfTheCmdlet() {
        Probe probe = new Probe();
        var closeFailure = new java.io.IOException("cannot close");
        probe.onBegin = context -> context.state(First.class, () -> {
            var resource = new First(probe.calls);
            resource.closeFailure = closeFailure;
            return resource;
        });
        probe.onProcess = (_, _) -> {
            throw new IllegalStateException("failure in process");
        };

        assertThatThrownBy(() -> run(probe, new RecordingSource("a"), "w")).isInstanceOf(IllegalStateException.class)
                .hasSuppressedException(closeFailure);
    }

    @Test
    void aFailureToCloseIsReportedWhenTheCmdletSucceeded() {
        Probe probe = new Probe();
        var closeFailure = new java.io.IOException("cannot close");
        probe.onBegin = context -> context.state(First.class, () -> {
            var resource = new First(probe.calls);
            resource.closeFailure = closeFailure;
            return resource;
        });

        assertThatThrownBy(() -> run(probe, null, "w")).isSameAs(closeFailure);
    }

    @Test
    void everyStateIsClosedEvenIfOneFailsToClose() {
        Probe probe = new Probe();
        probe.onBegin = context -> {
            context.state(First.class, () -> new First(probe.calls));
            context.state(Second.class, () -> {
                var resource = new Second(probe.calls);
                resource.closeFailure = new java.io.IOException("cannot close");
                return resource;
            });
        };

        assertThatThrownBy(() -> run(probe, null, "w")).isInstanceOf(java.io.IOException.class);

        assertThat(probe.calls).containsExactly("begin w", "end", "close second", "close first");
    }

    @Test
    void aStateMustExist() {
        Probe probe = new Probe();
        probe.onBegin = context -> context.state(Counter.class, () -> null);

        assertThatThrownBy(() -> run(probe, null, "w")).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Counter");
    }
}
