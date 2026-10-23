package io.powerj.fx.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class DebouncerTest {

    /** A clock that only moves when the test says so. */
    private static final class FakeScheduler implements Scheduler {
        record Task(Runnable action, Duration delay, boolean[] cancelled) {
        }

        final List<Task> tasks = new ArrayList<>();

        @Override
        public Scheduled schedule(Runnable task, Duration delay) {
            boolean[] cancelled = new boolean[1];
            tasks.add(new Task(task, delay, cancelled));
            return () -> cancelled[0] = true;
        }

        /** Lets the time pass: runs the tasks that were not cancelled. */
        void elapse() {
            for (Task task : List.copyOf(tasks)) {
                if (!task.cancelled()[0]) {
                    task.action().run();
                }
            }
            tasks.clear();
        }
    }

    private final FakeScheduler scheduler = new FakeScheduler();
    private final Debouncer debouncer = new Debouncer(scheduler, Duration.ofMillis(200));
    private final List<String> runs = new ArrayList<>();

    @Test
    void theActionRunsAfterTheDelay() {
        debouncer.submit(() -> runs.add("a"));
        assertThat(runs).isEmpty();
        assertThat(debouncer.isPending()).isTrue();
        assertThat(scheduler.tasks.getFirst().delay()).isEqualTo(Duration.ofMillis(200));
        scheduler.elapse();
        assertThat(runs).containsExactly("a");
        assertThat(debouncer.isPending()).isFalse();
    }

    @Test
    void aNewCallPostponesTheOneBefore() {
        debouncer.submit(() -> runs.add("a"));
        debouncer.submit(() -> runs.add("b"));
        debouncer.submit(() -> runs.add("c"));
        assertThat(scheduler.tasks).hasSize(3);
        scheduler.elapse();
        assertThat(runs).containsExactly("c");
    }

    @Test
    void cancelForgetsTheAction() {
        debouncer.submit(() -> runs.add("a"));
        debouncer.cancel();
        assertThat(debouncer.isPending()).isFalse();
        scheduler.elapse();
        assertThat(runs).isEmpty();
        debouncer.cancel(); // nothing to cancel: no failure
    }

    @Test
    void itCanBeUsedAgainAfterARun() {
        debouncer.submit(() -> runs.add("a"));
        scheduler.elapse();
        debouncer.submit(() -> runs.add("b"));
        scheduler.elapse();
        assertThat(runs).containsExactly("a", "b");
    }
}
