package io.powerj.core.runtime;

/**
 * A {@link Pipe} that counts the objects that cross it and the time its writer and its reader spend blocked (perf
 * mode, FR-76). The plain pipe has no measurement in it: the perf mode substitutes this one for it. Each counter has a
 * single writer, the thread of the stage on that side, which is why {@code +=} on a {@code volatile} is enough.
 */
public final class MeasuredPipe extends Pipe {

    private volatile long puts;
    private volatile long takes;
    private volatile long putBlockedNanos;
    private volatile long takeBlockedNanos;

    public long putCount() {
        return puts;
    }

    public long takeCount() {
        return takes;
    }

    /** Time the writer waited for space: the next stage was slower. */
    public long putBlockedNanos() {
        return putBlockedNanos;
    }

    /** Time the reader waited for an object: the previous stage was slower. */
    public long takeBlockedNanos() {
        return takeBlockedNanos;
    }

    @Override
    void enqueue(Object item) throws InterruptedException {
        if (!queue.offer(item)) {
            long start = System.nanoTime();
            try {
                queue.put(item);
            } finally {
                putBlockedNanos += System.nanoTime() - start;
            }
        }
        if (item != Source.END) {
            puts++;
        }
    }

    @Override
    Object dequeue() throws InterruptedException {
        Object item = queue.poll();
        if (item == null) {
            long start = System.nanoTime();
            try {
                item = queue.take();
            } finally {
                takeBlockedNanos += System.nanoTime() - start;
            }
        }
        if (item != Source.END) {
            takes++;
        }
        return item;
    }
}
