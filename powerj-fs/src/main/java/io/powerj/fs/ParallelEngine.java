package io.powerj.fs;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The engine of a parallel work on a file tree, shared by {@link FileWalker} and {@link FileTrees} (#96): tasks run in
 * virtual threads, at most {@code parallelism} of them hold a permit (an open folder or file) at the same time, and their
 * results and errors are handed over to the calling thread by a bounded queue, so that a slow consumer slows down the
 * work.
 * <ul>
 *   <li>{@link #run} submits the first task, then reads the queue in the calling thread and gives each result and each
 *       error to its consumer, until the last task has ended.</li>
 *   <li>An unchecked exception or an error of a task stops the work and is thrown back by {@link #run} (a checked one is
 *       wrapped in {@link IllegalStateException}); so is an exception of a consumer.</li>
 *   <li>An interruption of the calling thread (Ctrl+C) stops the work: {@link #run} throws {@link CancellationException},
 *       with the interrupt flag set.</li>
 * </ul>
 * Whatever the way it ends, {@link #run} returns only once every task has ended. One engine runs once.
 *
 * @param <T> type of the results
 * @param <E> type of the errors
 */
final class ParallelEngine<T, E> {

    /** Results and errors waiting for the calling thread. */
    static final int QUEUE_CAPACITY = 256;

    /** Work done in a virtual thread. */
    @FunctionalInterface
    interface Task {
        void run() throws Exception;
    }

    /** What the queue carries: the type says what it is, whatever the type of the result. */
    private sealed interface Item<T, E> {
    }

    private record Result<T, E>(T value) implements Item<T, E> {
    }

    private record Problem<T, E>(E error) implements Item<T, E> {
    }

    /** An unchecked exception or an error of a task, thrown back in the calling thread. */
    private record Failure<T, E>(Throwable exception) implements Item<T, E> {
    }

    /** The last task has ended. */
    private record End<T, E>() implements Item<T, E> {
    }

    private final BlockingQueue<Item<T, E>> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    /** Listings, visits, removals or copies in progress: bounds the open files. */
    private final Semaphore permits;
    /** Tasks submitted and not finished; the last one to finish signals the end. */
    private final AtomicInteger pending = new AtomicInteger();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean stopped;

    ParallelEngine(int parallelism) {
        this.permits = new Semaphore(parallelism);
    }

    /**
     * Submits {@code start}, then gives the results and errors to their consumers, in the calling thread, until every task
     * has ended.
     *
     * @throws CancellationException if the calling thread is interrupted
     */
    void run(Task start, Consumer<? super T> results, Consumer<? super E> errors) {
        try (executor) {
            try {
                submit(start);
                while (true) {
                    switch (queue.take()) {
                        case Result<T, E>(T value) -> results.accept(value);
                        case Problem<T, E>(E error) -> errors.accept(error);
                        case Failure<T, E>(Throwable e) when e instanceof RuntimeException r -> throw r;
                        case Failure<T, E>(Throwable e) when e instanceof Error error -> throw error;
                        case Failure<T, E>(Throwable e) -> throw new IllegalStateException(e);
                        case End<T, E>() -> {
                            return;
                        }
                    }
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                throw new CancellationException();
            } finally {
                stopped = true;
                executor.shutdownNow(); // interrupts the workers; close() waits for them
            }
        }
    }

    /** {@code true} once the work is stopped (ended, failed or cancelled): a task should not start anything more. */
    boolean stopped() {
        return stopped;
    }

    /** Hands a result to the calling thread; waits while the queue is full. */
    void result(T value) throws InterruptedException {
        queue.put(new Result<>(value));
    }

    /** Hands an error to the calling thread; waits while the queue is full. */
    void error(E error) throws InterruptedException {
        queue.put(new Problem<>(error));
    }

    /** Takes a permit, waiting for a free one. */
    void acquire() throws InterruptedException {
        permits.acquire();
    }

    void release() {
        permits.release();
    }

    /**
     * Runs a task in its own virtual thread.
     *
     * @return {@code false} if the work is already stopped: the task will not run
     */
    boolean submit(Task task) {
        pending.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Throwable e) {
                    fail(e);
                } finally {
                    if (pending.decrementAndGet() == 0) {
                        signal(new End<>());
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException _) {
            pending.decrementAndGet(); // work already stopped
            return false;
        }
    }

    /**
     * Waits for a free permit, then runs {@code held} in its own virtual thread with that permit, and {@code then} once the
     * permit is released (if {@code held} ended normally). Waiting here, in the task that submits, bounds the tasks that
     * wait for a permit too.
     */
    void submitHolding(Task held, Task then) throws InterruptedException {
        permits.acquire();
        boolean submitted = false;
        try {
            submitted = submit(() -> {
                try {
                    held.run();
                } finally {
                    permits.release();
                }
                then.run();
            });
        } finally {
            if (!submitted) {
                permits.release();
            }
        }
    }

    private void fail(Throwable e) {
        if (stopped || e instanceof InterruptedException || e instanceof CancellationException) {
            return; // consequence of the stop
        }
        stopped = true;
        deliver(new Failure<>(e)); // read by the calling thread, which waits for it
    }

    /** Signals the end, unless the work is stopped: nobody reads anymore, and a full queue would block. */
    private void signal(Item<T, E> item) {
        if (!stopped) {
            deliver(item);
        }
    }

    private void deliver(Item<T, E> item) {
        try {
            queue.put(item);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt(); // work stopped: nobody reads anymore
        }
    }
}
