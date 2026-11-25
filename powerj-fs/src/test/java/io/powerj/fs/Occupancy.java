package io.powerj.fs;

import java.util.concurrent.atomic.AtomicInteger;

/** Counts the operations in progress and keeps the highest count seen: how many ran at the same time (#96). */
final class Occupancy {

    private final AtomicInteger current = new AtomicInteger();
    private final AtomicInteger max = new AtomicInteger();

    void enter() {
        int now = current.incrementAndGet();
        max.accumulateAndGet(now, Math::max);
    }

    void exit() {
        current.decrementAndGet();
    }

    /** The highest number of operations in progress at the same time so far. */
    int max() {
        return max.get();
    }
}
