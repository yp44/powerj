package io.powerj.shell;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Waiting for a state instead of for a duration (#95): the condition is polled until it holds and the wait fails after
 * the limit. Between two polls the thread parks, as {@code io.powerj.core.support.Waits} does in powerj-core, so no
 * test sleeps to wait. The limit is only a way to fail instead of hanging: keep it far above what the runner needs.
 */
public final class Awaits {

    private static final Duration LIMIT = Duration.ofSeconds(20);

    private Awaits() {
    }

    /** Returns when {@code condition} holds, within the default limit of twenty seconds. */
    public static void awaitUntil(BooleanSupplier condition) {
        awaitUntil("the awaited condition", condition, LIMIT);
    }

    /** Returns when {@code condition} holds, within {@code limit}. */
    public static void awaitUntil(String what, BooleanSupplier condition, Duration limit) {
        long deadline = System.nanoTime() + limit.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("never happened: " + what);
            }
            LockSupport.parkNanos(200_000);
        }
    }
}
