package io.powerj.fx.model;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The link between the pipeline, which produces the rows, and the window, which shows them while they arrive.
 * <p>
 * The pipeline calls {@link #offer} from its own thread; the queue is bounded, so a pipeline faster than the window waits
 * (the window sets the pace, as a slow stage does for the next one). The window calls {@link #drain} on the toolkit
 * thread, a batch at a time, so that it is never flooded. When the window is closed ({@link #close()}) the pipeline is
 * told to stop reading.
 */
public final class LiveTable {

    /** Where the input stands. */
    public enum State {
        /** Rows may still arrive. */
        LOADING,
        /** The input ended. */
        COMPLETE,
        /** The input was cut at the limit of rows. */
        TRUNCATED,
        /** The pipeline stopped without saying so (Ctrl+C, an error): what arrived is all there is. */
        INTERRUPTED
    }

    private enum Done { NO, COMPLETE, TRUNCATED }

    private static final long WAIT_MILLIS = 50;

    private final BlockingQueue<Reading> queue;
    private volatile boolean closed;
    private volatile boolean paused;
    private volatile Done done = Done.NO;
    private volatile Thread producer;
    private final CountDownLatch closedLatch = new CountDownLatch(1);
    private final List<Runnable> closeListeners = new CopyOnWriteArrayList<>();

    /** @param capacity rows that can wait for the window; the pipeline waits beyond */
    public LiveTable(int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /**
     * Gives a row to the window, waiting if it is far behind.
     *
     * @return {@code false} if the window was closed: the pipeline should stop reading
     * @throws InterruptedException if the pipeline is cancelled (Ctrl+C) while it waits
     */
    public boolean offer(Reading reading) throws InterruptedException {
        if (producer == null) {
            producer = Thread.currentThread();
        }
        while (!closed) {
            if (queue.offer(reading, WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                if (closed) { // the window closed while the row went in: nobody will read it
                    queue.clear();
                    return false;
                }
                return true;
            }
        }
        return false;
    }

    /** The input ended; {@code truncated} if it was cut at the limit of rows. */
    public void finish(boolean truncated) {
        done = truncated ? Done.TRUNCATED : Done.COMPLETE;
    }

    /**
     * The window is gone, or is to go: nothing is waited for any more, {@link #offer} says no, {@link #awaitClosed} returns
     * and the listeners run. Calling it again does nothing.
     */
    public void close() {
        boolean first = !closed;
        closed = true;
        queue.clear();
        if (first) {
            closedLatch.countDown();
            closeListeners.forEach(Runnable::run);
        }
    }

    /** Runs {@code action} when {@link #close()} is called (at once if it was). It runs on the thread that closes. */
    public void onClosed(Runnable action) {
        closeListeners.add(action);
        if (closed) {
            action.run();
        }
    }

    /**
     * Waits until the window is closed ({@code --wait}).
     *
     * @throws InterruptedException if the pipeline is cancelled (Ctrl+C) while it waits
     */
    public void awaitClosed() throws InterruptedException {
        closedLatch.await();
    }

    public boolean isClosed() {
        return closed;
    }

    /**
     * Stops (or starts again) giving the rows that wait to the table. The queue fills up, so the pipeline waits in its turn:
     * the loading is really suspended, a slow command is not read faster than the window takes it.
     */
    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean isPaused() {
        return paused;
    }

    /**
     * Moves up to {@code max} rows that wait into the table, none while the loading is paused. Called on the toolkit
     * thread.
     *
     * @return how many were moved
     */
    public int drain(GridTable table, int max) {
        if (paused) {
            return 0;
        }
        List<Reading> batch = new ArrayList<>();
        queue.drainTo(batch, max);
        if (!batch.isEmpty()) {
            table.append(batch);
        }
        return batch.size();
    }

    /** Rows that wait for the window. */
    public int pending() {
        return queue.size();
    }

    /**
     * Where the input stands. A pipeline whose thread is gone without having called {@link #finish} was cancelled or failed
     * ({@code end} is not called then): that is {@link State#INTERRUPTED}.
     */
    public State state() {
        return switch (done) {
            case COMPLETE -> State.COMPLETE;
            case TRUNCATED -> State.TRUNCATED;
            case NO -> {
                Thread thread = producer;
                yield thread != null && !thread.isAlive() ? State.INTERRUPTED : State.LOADING;
            }
        };
    }
}
