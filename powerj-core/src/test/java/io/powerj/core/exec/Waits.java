package io.powerj.core.exec;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Waiting for a state instead of for a duration (review C-31): "sleep 300 ms, then interrupt" interrupts nothing on a
 * loaded machine and wastes time on a fast one. A wait ends as soon as its condition holds and fails after ten seconds.
 */
final class Waits {

    private static final long LIMIT_NANOS = TimeUnit.SECONDS.toNanos(10);

    private Waits() {
    }

    /** Returns when {@code condition} holds. */
    static void until(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + LIMIT_NANOS;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("never happened: " + what);
            }
            LockSupport.parkNanos(200_000);
        }
    }
}
