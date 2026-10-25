package io.powerj.core.exec;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Link between two stages of a pipeline: bounded queue (the fast stage waits for the slow one, memory stays
 * constant), end of stream, and stop requested by the next stage when it no longer reads.
 */
class Pipe implements Source {

    /** Represents {@code null} in the queue. */
    private static final Object NULL = new Object();

    private static final int CAPACITY = 256;

    final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(CAPACITY);
    private volatile boolean aborted;
    private final List<Runnable> onAbort = new CopyOnWriteArrayList<>();

    /** Action run when the reader gives up: stop the writer (thread interrupted, processes stopped). */
    void onAbort(Runnable action) {
        onAbort.add(action);
        if (aborted) {
            action.run();
        }
    }

    boolean aborted() {
        return aborted;
    }

    /**
     * Adds an object, waiting for space.
     *
     * @throws CancellationException if the reader has given up or if the thread is interrupted
     */
    void put(Object value) {
        if (aborted) {
            throw new CancellationException("pipeline closed by the next stage");
        }
        try {
            enqueue(value == null ? NULL : value);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("pipeline interrupted");
        }
    }

    /** Puts an item in the queue, waiting for space; {@link MeasuredPipe} counts what it does here. */
    void enqueue(Object item) throws InterruptedException {
        queue.put(item);
    }

    /** Takes an item from the queue, waiting for one; {@link MeasuredPipe} counts what it does here. */
    Object dequeue() throws InterruptedException {
        return queue.take();
    }

    /** Adds an object, unrolling collections, arrays, streams and optionals (FR-30b). */
    void putUnrolled(Object value) {
        unroll(value, this);
    }

    /** Signals the end of the stream (no effect if the reader has given up). */
    void close() {
        if (!aborted) {
            put(Source.END);
        }
    }

    @Override
    public Object next() throws InterruptedException {
        Object value = dequeue();
        return value == NULL ? null : value;
    }

    @Override
    public void abort() {
        if (!aborted) {
            aborted = true;
            queue.clear();
            onAbort.forEach(Runnable::run);
        }
    }

    /**
     * Emits {@code value}, or each of its elements if it is an {@link Iterable} (except {@code Path}), an
     * array, a stream ({@code Stream}, {@code IntStream}, {@code LongStream}, {@code DoubleStream}), an {@link Iterator} or an {@link Optional} (also {@code OptionalInt}…). Strings and {@code Map}s
     * are never unrolled, nor is a {@link io.powerj.api.Collected} list produced by {@code collect}.
     */
    static void unroll(Object value, Pipe target) {
        if (!ValueUnroller.unroll(value, ValueUnroller.Mode.BETWEEN_STAGES, target::put)) {
            target.put(value);
        }
    }

}
