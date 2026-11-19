package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.error.PjException;
import io.powerj.core.support.FakeCmdlets;
import io.powerj.core.support.Waits;

/**
 * A line that a second Ctrl+C abandoned goes on running on its thread (FR-03). What it writes in the state of the
 * interpreter, when it ends, belongs to no line any more and must not land in the next one (audit v2, S-02, #172); and
 * the display of a value that never ends must stop at Ctrl+C (S-03).
 */
class StaleLineTest {

    /** Ignores the interruption, as a computation of the JDK does, until the test lets it go, then fails. */
    @CmdletInfo(name = "spin", category = "Test", summary = "Spins, then fails", examples = "spin")
    static final class Spin implements Cmdlet<Spin.Params, Void, Object> {

        record Params() { }

        final CountDownLatch started = new CountDownLatch(1);
        final AtomicBoolean release = new AtomicBoolean();

        @Override
        public void begin(Params params, CmdletContext<Object> context) {
            started.countDown();
            while (!release.get()) {
                Thread.onSpinWait();
            }
            throw new PjException("spin: ended badly"); // a blocking error of a line that is over
        }
    }

    /** Holds the line it is in, like {@link Spin}, and ends well. */
    @CmdletInfo(name = "park", category = "Test", summary = "Waits", examples = "park")
    static final class Park implements Cmdlet<Park.Params, Void, Object> {

        record Params() { }

        final CountDownLatch started = new CountDownLatch(1);
        final AtomicBoolean release = new AtomicBoolean();

        @Override
        public void begin(Params params, CmdletContext<Object> context) {
            started.countDown();
            while (!release.get()) {
                Thread.onSpinWait();
            }
        }
    }

    /** Counts what each measured line was told: the pipelines it entered. */
    private static final class CountingMeasuring implements LineMeasuring {
        final List<AtomicInteger> pipelinesByLine = new ArrayList<>();

        @Override
        public boolean on() {
            return true;
        }

        @Override
        public void on(boolean on) {
        }

        @Override
        public Measurement start(String line, io.powerj.core.lang.Ast.Script script, long beginNanos, long parseNanos) {
            var entered = new AtomicInteger();
            pipelinesByLine.add(entered);
            ExecutionObserver observer = new ExecutionObserver() {
                @Override
                public PipelineObserver pipelineEntered() {
                    entered.incrementAndGet();
                    return PipelineObserver.NONE;
                }

                @Override
                public void displayed(long nanos) {
                }
            };
            return new Measurement() {
                @Override
                public ExecutionObserver observer() {
                    return observer;
                }

                @Override
                public void finish(boolean cancelled) {
                }
            };
        }
    }

    @TempDir
    Path tmp;

    private final Supervisor supervisor = new Supervisor();
    private final Spin spin = new Spin();
    private final Park park = new Park();
    private final CountingMeasuring measuring = new CountingMeasuring();
    private final AtomicInteger written = new AtomicInteger();
    private Session session;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        var cmdlets = new ArrayList<Cmdlet<?, ?, ?>>(FakeCmdlets.all());
        cmdlets.add(spin);
        cmdlets.add(park);
        session = new Session(tmp, tmp, Map.of("PATH", ""));
        // The values are counted, not kept: a display that never ends would fill the memory.
        var discard = new Writer() {
            @Override
            public void write(char[] buffer, int offset, int length) {
                written.addAndGet(length);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(discard, true), _ -> { }, false), Map.of(),
                CmdletRegistry.of(cmdlets), measuring);
    }

    private Thread start(String line, AtomicReference<Outcome> outcome) {
        return Thread.ofVirtual().start(() -> outcome.set(supervisor.run(line, () -> {
            interpreter.execute(line);
            return List.of();
        })));
    }

    private static long commandThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("powerj-command") && t.isAlive()).count();
    }

    @Test
    void anAbandonedLineThatEndsLaterTouchesNothingOfTheLineThatFollows() throws Exception {
        var first = new AtomicReference<Outcome>();
        Thread abandoned = start("spin", first);
        assertThat(spin.started.await(10, TimeUnit.SECONDS)).isTrue();
        supervisor.cancel();
        supervisor.cancel(); // the second Ctrl+C abandons the line, which goes on spinning
        abandoned.join(TimeUnit.SECONDS.toMillis(10));
        assertThat(first.get()).isEqualTo(new Outcome.Abandoned("spin"));
        Object resultBefore = session.variable("?");

        var second = new AtomicReference<Outcome>();
        Thread next = start("park; items", second);
        assertThat(park.started.await(10, TimeUnit.SECONDS)).isTrue();
        spin.release.set(true); // the old line ends now, in the middle of the new one
        Waits.until("the abandoned line has ended", () -> commandThreads() == 1);

        assertThat(session.variable("?")).as("$? is not written by the old line").isEqualTo(resultBefore);
        park.release.set(true);
        next.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(second.get()).isInstanceOf(Outcome.Success.class);
        assertThat(interpreter.hadBlockingError()).as("the error of the old line is not this line's").isFalse();
        assertThat(measuring.pipelinesByLine.get(1)).as("the new line stayed measured to its end").hasValue(2);
    }

    @Test
    void theDisplayOfAValueThatNeverEndsStopsAtCtrlC() throws Exception {
        var outcome = new AtomicReference<Outcome>();
        Thread line = start("java.util.stream.IntStream.range(0, 2147483647)", outcome);
        Waits.until("the values are being displayed", () -> written.get() > 1000);

        supervisor.cancel();
        line.join(TimeUnit.SECONDS.toMillis(1));

        assertThat(line.isAlive()).as("the line ended within a second").isFalse();
        assertThat(outcome.get()).isEqualTo(new Outcome.Cancelled());
        Waits.until("the worker thread is gone", () -> commandThreads() == 0);
    }
}
