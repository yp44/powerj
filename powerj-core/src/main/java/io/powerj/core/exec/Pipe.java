package io.powerj.core.exec;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.stream.BaseStream;

/**
 * Link between two stages of a pipeline: bounded queue (the fast stage waits for the slow one, memory stays
 * constant), end of stream, and stop requested by the next stage when it no longer reads.
 */
final class Pipe implements Source {

    /** Represents {@code null} in the queue. */
    private static final Object NULL = new Object();

    private static final int CAPACITY = 256;

    private final BlockingQueue<Object> queue = new ArrayBlockingQueue<>(CAPACITY);
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
            throw new CancellationException("pipeline fermé par l'étape suivante");
        }
        try {
            queue.put(value == null ? NULL : value);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("pipeline interrompu");
        }
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
        Object value = queue.take();
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
        switch (value) {
            case java.nio.file.Path path -> target.put(path);
            case io.powerj.api.Collected<?> list -> target.put(list); // collect: the list is passed whole (FR-36d)
            case Iterable<?> items -> items.forEach(target::put);
            case BaseStream<?, ?> stream -> { // Stream, and IntStream, LongStream, DoubleStream (boxed elements)
                try (stream) {
                    stream.iterator().forEachRemaining(target::put);
                }
            }
            case Iterator<?> iterator -> iterator.forEachRemaining(target::put);
            case Optional<?> optional -> optional.ifPresent(target::put);
            case OptionalInt optional -> optional.ifPresent(target::put);
            case OptionalLong optional -> optional.ifPresent(target::put);
            case OptionalDouble optional -> optional.ifPresent(target::put);
            case Object array when array.getClass().isArray() -> {
                for (int i = 0; i < java.lang.reflect.Array.getLength(array); i++) {
                    target.put(java.lang.reflect.Array.get(array, i));
                }
            }
            case null, default -> target.put(value);
        }
    }

}
