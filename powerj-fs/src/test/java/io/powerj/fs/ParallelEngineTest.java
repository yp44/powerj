package io.powerj.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** What {@link ParallelEngine} does on its own, apart from the walks that use it (#96). */
class ParallelEngineTest {

    private final List<String> results = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    @Test
    void itGivesTheResultsAndErrorsOfItsTasksToTheCallingThread() {
        var engine = new ParallelEngine<String, String>(2);
        Thread caller = Thread.currentThread();
        List<Thread> consumers = new ArrayList<>();

        engine.run(() -> {
            engine.submit(() -> engine.result("a"));
            engine.submitHolding(() -> engine.error("b"), () -> engine.result("c"));
        }, result -> {
            consumers.add(Thread.currentThread());
            results.add(result);
        }, errors::add);

        assertThat(results).containsExactlyInAnyOrder("a", "c");
        assertThat(errors).containsExactly("b");
        assertThat(consumers).containsOnly(caller);
    }

    /** A second run would wait for ever on a queue that its closed executor never fills: it fails instead. */
    @Test
    void anEngineRunsOnce() {
        var engine = new ParallelEngine<String, String>(2);
        engine.run(() -> engine.result("first"), results::add, errors::add);

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThatThrownBy(
                () -> engine.run(() -> engine.result("second"), results::add, errors::add))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("a ParallelEngine runs once"));
        assertThat(results).containsExactly("first");
    }
}
