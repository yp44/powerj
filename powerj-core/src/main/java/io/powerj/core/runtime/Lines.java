package io.powerj.core.runtime;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Which line the interpreter is executing. A line abandoned by a second Ctrl+C (FR-03) is not stopped: its thread goes
 * on, and when it ends it would write what the interpreter keeps for the line that runs now: {@code $?}, {@code $last},
 * {@code $errors}, the blocking-error flag, who measures the line (audit v2, S-02). Each line runs inside a scope that
 * holds its number; the writes of a thread whose line has been overtaken are ignored ({@link #stale()}).
 */
final class Lines {

    /** The number of the line that the current thread (or the stage thread it started) runs; unbound outside a line. */
    private static final ScopedValue<Long> RUNNING = ScopedValue.newInstance();

    private final AtomicLong latest = new AtomicLong();

    /** A new line begins; the one that was running, if it still is, is stale from now on. */
    long begin() {
        return latest.incrementAndGet();
    }

    /** Runs {@code body} as the line {@code line}. */
    <X extends Throwable> void run(long line, ScopedValue.CallableOp<Void, X> body) throws X {
        ScopedValue.where(RUNNING, line).call(body);
    }

    /** {@code true} if {@code line} is the line of the interpreter. */
    boolean isLatest(long line) {
        return latest.get() == line;
    }

    /** {@code true} if the current thread runs a line that a later line has overtaken: its writes belong to nothing. */
    boolean stale() {
        return RUNNING.isBound() && RUNNING.get() != latest.get();
    }

    /** {@code task}, which will run as the line of the current thread (a pipeline stage thread). */
    Runnable propagate(Runnable task) {
        if (!RUNNING.isBound()) {
            return task;
        }
        long line = RUNNING.get();
        return () -> ScopedValue.where(RUNNING, line).run(task);
    }
}
