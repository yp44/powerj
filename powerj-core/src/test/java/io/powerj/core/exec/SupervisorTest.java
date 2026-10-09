package io.powerj.core.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class SupervisorTest {

    private final Supervisor supervisor = new Supervisor();

    @Test
    void successCarriesTheValues() {
        assertThat(supervisor.run("ok", () -> List.of("a", 1)))
                .isEqualTo(new Outcome.Success(List.of("a", 1)));
    }

    @Test
    void expectedErrorKeepsItsMessage() {
        var outcome = supervisor.run("x", () -> {
            throw new PjException("commande inconnue : x");
        });

        assertThat(outcome).isInstanceOfSatisfying(Outcome.Failure.class,
                f -> assertThat(f.error().message()).isEqualTo("commande inconnue : x"));
    }

    @Test
    void stackOverflowDoesNotEscape() {
        var outcome = supervisor.run("rec", () -> List.of(recurse(0)));

        assertThat(outcome).isInstanceOfSatisfying(Outcome.Failure.class,
                f -> assertThat(f.error().message()).isEqualTo("recursion too deep"));
    }

    @Test
    void outOfMemoryDoesNotEscapeAndShellCanContinue() {
        var outcome = supervisor.run("oom", () -> {
            throw new OutOfMemoryError("Java heap space");
        });

        assertThat(outcome).isInstanceOfSatisfying(Outcome.Failure.class,
                f -> assertThat(f.error().message()).startsWith("out of memory"));
        assertThat(supervisor.run("next", List::of)).isEqualTo(new Outcome.Success(List.of()));
    }

    @Test
    void unexpectedExceptionBecomesAnInternalError() {
        var outcome = supervisor.run("bug", () -> {
            throw new IllegalStateException("boom");
        });

        assertThat(outcome).isInstanceOfSatisfying(Outcome.Failure.class, f -> {
            assertThat(f.error().message()).contains("internal error", "boom");
            assertThat(f.error().cause()).containsInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void cancelInterruptsTheRunningCommand() throws Exception {
        var started = new CountDownLatch(1);
        var result = new AtomicReference<Outcome>();
        var worker = Thread.ofVirtual().start(() -> result.set(supervisor.run("sleep", () -> {
            started.countDown();
            Thread.sleep(TimeUnit.MINUTES.toMillis(1));
            return List.of();
        })));

        started.await();
        supervisor.cancel();
        worker.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(result.get()).isEqualTo(new Outcome.Cancelled());
    }

    @Test
    void secondCancelAbandonsACommandThatIgnoresInterruption() throws Exception {
        var started = new CountDownLatch(1);
        var stop = new java.util.concurrent.atomic.AtomicBoolean();
        var result = new AtomicReference<Outcome>();
        var caller = Thread.ofVirtual().start(() -> result.set(supervisor.run("boucle", () -> {
            started.countDown();
            while (!stop.get()) {
                Thread.onSpinWait(); // ignores the interruption, like a JDK computation
            }
            return List.of();
        })));

        started.await();
        supervisor.cancel();
        caller.join(300);
        assertThat(caller.isAlive()).isTrue();
        supervisor.cancel();
        caller.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(result.get()).isEqualTo(new Outcome.Abandoned("boucle"));
        stop.set(true);
    }

    @Test
    void cancelWithoutRunningCommandIsHarmless() {
        supervisor.cancel();

        assertThat(supervisor.run("ok", List::of)).isEqualTo(new Outcome.Success(List.of()));
    }

    private static int recurse(int depth) {
        return recurse(depth + 1) + 1;
    }
}
