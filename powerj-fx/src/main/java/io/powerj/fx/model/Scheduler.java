package io.powerj.fx.model;

import java.time.Duration;

/** Runs a task later; the window uses the toolkit's, the tests their own. */
public interface Scheduler {

    /** Runs {@code task} once, after {@code delay}, unless the result is cancelled first. */
    Scheduled schedule(Runnable task, Duration delay);

    /** A task that waits to run. */
    interface Scheduled {

        void cancel();
    }
}
