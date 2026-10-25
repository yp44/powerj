package io.powerj.fx;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Waiting for a thread to reach a state instead of for a duration (review C-31): "give it 150 ms and assert that it is
 * still blocked" proves nothing on a loaded machine and wastes time on a fast one.
 */
public final class Threads {

    private Threads() {
    }

    /** A body running on its own virtual thread: the thread to observe, and its result as a future. */
    public record Task<T>(Thread thread, CompletableFuture<T> result) {
    }

    public static <T> Task<T> start(Callable<T> body) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread thread = Thread.ofVirtual().unstarted(() -> {
            try {
                result.complete(body.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        thread.start();
        return new Task<>(thread, result);
    }

    /**
     * Returns once the thread is parked or waiting (on a lock, a queue, a latch, a monitor): the moment from which a
     * "still blocked" assertion means something.
     *
     * @throws AssertionError if the thread ended instead, or if nothing happened within ten seconds
     */
    public static void awaitParked(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            switch (thread.getState()) {
                case WAITING, TIMED_WAITING, BLOCKED -> {
                    return;
                }
                case TERMINATED -> throw new AssertionError("the thread ended instead of blocking");
                default -> {
                    if (System.nanoTime() > deadline) {
                        throw new AssertionError("the thread never blocked, it is " + thread.getState());
                    }
                    LockSupport.parkNanos(100_000);
                }
            }
        }
    }
}
