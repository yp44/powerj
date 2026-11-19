package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.error.PjException;
import io.powerj.core.support.Waits;

/**
 * Ctrl+C while a Java method blocks (FR-03, FR-57; audit v2 R-01, #165): the JDK clears the interrupt flag when it
 * throws {@link InterruptedException}, so the interruption must become a cancellation, not an ordinary error that lets
 * the next statement of the line run.
 */
class JavaCallCancellationTest {

    @TempDir
    Path tmp;

    private final Supervisor supervisor = new Supervisor();
    private final StringWriter out = new StringWriter();
    private final List<String> errors = new ArrayList<>();
    private Session session;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tmp.resolve("home"));
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("HOME", tmp.resolve("home").toString());
        session = new Session(tmp.resolve("home"), tmp, environment);
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(out, true), errors::add, false), Map.of(),
                CmdletRegistry.of(List.of()));
    }

    /** Runs the line under the supervisor, cancels it once it has been running for a moment, and returns the outcome. */
    private Outcome runAndCancel(String line) throws Exception {
        var result = new AtomicReference<Outcome>();
        var caller = Thread.ofVirtual().start(() -> result.set(supervisor.run(line, () -> {
            interpreter.execute(line);
            return List.of();
        })));
        Waits.until("the line is blocked in a Java call", JavaCallCancellationTest::aCommandIsBlocked);
        long begin = System.nanoTime();
        supervisor.cancel();
        caller.join(TimeUnit.SECONDS.toMillis(10));
        assertThat(caller.isAlive()).as("the line ended").isFalse();
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - begin)).isLessThan(5);
        return result.get();
    }

    private static boolean aCommandIsBlocked() {
        return Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.getName().equals("powerj-command")
                && (t.getState() == Thread.State.TIMED_WAITING || t.getState() == Thread.State.WAITING));
    }

    @Test
    void ctrlCDuringASleepCancelsTheLineAndTheNextStatementDoesNotRun() throws Exception {
        Outcome outcome = runAndCancel("Thread.sleep(30000); $after = 1");

        assertThat(outcome).isEqualTo(new Outcome.Cancelled());
        assertThatThrownBy(() -> session.variable("after")).isInstanceOf(PjException.class); // never assigned
    }

    @Test
    void ctrlCInABlockCalledFromJavaEndsTheLineAsACancellation() throws Exception {
        Outcome outcome = runAndCancel("java.util.stream.IntStream.range(0, 1000).forEach({ Thread.sleep(200) })");

        assertThat(outcome).isEqualTo(new Outcome.Cancelled());
        assertThat(errors).isEmpty();
    }

    @Test
    void anAbandonedLineStopsAtItsNextStatement() throws Exception {
        var result = new AtomicReference<Outcome>();
        var caller = Thread.ofVirtual().start(() -> result.set(supervisor.run("x", () -> {
            Thread.currentThread().interrupt(); // as the flag a cancel leaves behind
            interpreter.execute("$after = 1; $again = 2");
            return List.of();
        })));
        caller.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(result.get()).isEqualTo(new Outcome.Cancelled());
        assertThatThrownBy(() -> session.variable("after")).isInstanceOf(PjException.class); // never assigned
    }
}
