package io.powerj.fx.model;

import java.time.Duration;

/**
 * Runs an action once the calls stop: each {@link #submit} postpones the previous one, so that typing in a filter
 * does not refilter the table at every key.
 */
public final class Debouncer {

    private final Scheduler scheduler;
    private final Duration delay;
    private Scheduler.Scheduled pending;

    public Debouncer(Scheduler scheduler, Duration delay) {
        this.scheduler = scheduler;
        this.delay = delay;
    }

    /** Runs {@code action} after the delay, unless {@code submit} is called again before. */
    public void submit(Runnable action) {
        cancel();
        pending = scheduler.schedule(() -> {
            pending = null;
            action.run();
        }, delay);
    }

    /** Forgets the action that waits. */
    public void cancel() {
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
    }

    public boolean isPending() {
        return pending != null;
    }
}
