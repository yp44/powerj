package io.powerj.core.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The state of a session, and its use from several threads at once (review C-13). */
class SessionTest {

    @TempDir
    Path tmp;

    private Session session() {
        return new Session(tmp, tmp, Map.of("PATH", "/bin", "LANG", "C"));
    }

    @Test
    void aVariableMayHoldNull() {
        var session = session();
        session.setVariable("n", null);

        assertThat(session.variable("n")).isNull();
        assertThat(session.variableNames()).contains("n");
    }

    @Test
    void anUnknownVariableIsAnError() {
        assertThatThrownBy(() -> session().variable("nope")).isInstanceOf(PjException.class)
                .hasMessage("unknown variable: $nope");
    }

    @Test
    void aVariableIsReplacedByTheNextValue() {
        var session = session();
        session.setVariable("x", 1);
        session.setVariable("x", "two");

        assertThat(session.variable("x")).isEqualTo("two");
    }

    @Test
    void theAutomaticVariablesTakePrecedenceOverTheUsers() {
        var session = session();
        session.setVariable("pwd", "mine");

        assertThat(session.variable("pwd")).isEqualTo(tmp.toAbsolutePath().normalize());
    }

    @Test
    void theAutomaticVariablesHaveTheirValues() {
        var session = session();

        assertThat(session.variable("?")).isEqualTo(true);
        assertThat(session.variable("exit")).isNull();
        assertThat(session.variable("last")).isNull();
        assertThat(session.variable("pwd")).isEqualTo(tmp.toAbsolutePath().normalize());
        assertThat(session.variable("home")).isEqualTo(tmp.toAbsolutePath().normalize());
        assertThat(session.variable("errors")).isEqualTo(List.of());
        assertThat(session.variable("perf")).isNull();

        session.recordSuccess(false);
        var error = new RuntimeException("boom");
        session.recordError(error);

        assertThat(session.variable("?")).isEqualTo(false);
        assertThat(session.variable("errors")).isEqualTo(List.of(error));
    }

    @Test
    void theNamesOfTheVariablesAreSortedAndIncludeTheAutomaticOnes() {
        var session = session();
        session.setVariable("zeta", 1);
        session.setVariable("alpha", 2);

        assertThat(session.variableNames()).containsExactly("?", "alpha", "errors", "exit", "home", "last", "perf",
                "pwd", "zeta");
    }

    @Test
    void theExitRequestIsKept() {
        var session = session();
        assertThat(session.exitRequest()).isEmpty();

        session.requestExit(0);
        assertThat(session.exitRequest()).hasValue(0);

        session.requestExit(7);
        assertThat(session.exitRequest()).hasValue(7);
    }

    // --- The snapshot of the environment ---

    @Test
    void theSnapshotOfTheEnvironmentIsImmutable() {
        var snapshot = session().environmentSnapshot();

        assertThat(snapshot).containsEntry("PATH", "/bin").containsEntry("LANG", "C");
        assertThatThrownBy(() -> snapshot.put("X", "1")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.remove("PATH")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theSnapshotDoesNotFollowLaterChanges() {
        var session = session();
        var before = session.environmentSnapshot();

        session.environment().put("NEW", "1");
        session.environment().remove("LANG");

        assertThat(before).containsEntry("LANG", "C").doesNotContainKey("NEW");
        assertThat(session.environmentSnapshot()).containsEntry("NEW", "1").doesNotContainKey("LANG");
    }

    // --- Several threads (C-13: copying a map while another thread changes it threw ConcurrentModificationException) ---

    private static final int ROUNDS = 100;
    private static final int ENTRIES = 2_000;

    /**
     * Fills a session on another thread while this one reads it in a loop. Repeated on fresh sessions, so that the
     * structure of the map changes under the reader many times.
     */
    private void readWhileFilling(BiConsumer<Session, Integer> add, Consumer<Session> read) throws InterruptedException {
        for (int round = 0; round < ROUNDS; round++) {
            var session = session();
            var done = new AtomicBoolean();
            Thread writer = Thread.ofPlatform().start(() -> {
                for (int i = 0; i < ENTRIES; i++) {
                    add.accept(session, i);
                }
                done.set(true);
            });
            try {
                while (!done.get()) {
                    read.accept(session);
                }
            } finally {
                writer.join();
            }
        }
    }

    @Test
    void theEnvironmentCanBeCopiedWhileItChanges() throws InterruptedException {
        var session = session();
        var stop = new AtomicBoolean();
        Thread writer = Thread.ofPlatform().start(() -> {
            for (int i = 0; !stop.get(); i++) {
                session.environment().put("K" + i % 50, "v");
                session.environment().remove("K" + (i + 25) % 50);
            }
        });
        try {
            for (int i = 0; i < 20_000; i++) {
                assertThat(session.environmentSnapshot()).containsKey("PATH");
            }
        } finally {
            stop.set(true);
            writer.join();
        }
    }

    @Test
    void theNamesOfTheVariablesCanBeListedWhileTheyAreAdded() throws InterruptedException {
        readWhileFilling((session, i) -> session.setVariable("v" + i, i),
                session -> assertThat(session.variableNames()).contains("pwd"));
    }
}
