package io.powerj.shell;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Evaluates the latest request and forgets the ones it overtook: what the preview needs while the user types. Each
 * {@link #submit} replaces the one before; after a short pause (so that typing fast does not start an evaluation per key)
 * a single worker thread evaluates the request, and that evaluation is interrupted if a newer request arrives or if it
 * lasts longer than the deadline (what it produced until then is what it returns). The result is only handed over if
 * its request is still the latest.
 * <p>
 * This used to be a monitor written by hand inside {@code Previewer}, with four counters, a deadline thread and two
 * waits, and it could only be tested by typing.
 *
 * @param <Q> what is asked for
 * @param <R> what the evaluation gives
 */
final class LatestWinsRunner<Q, R> {

    private static final Logger LOG = Logger.getLogger(LatestWinsRunner.class.getName());

    /** What the publisher is given to hand its result over. */
    interface Delivery {

        /**
         * Runs {@code action}, with the guarantee that no request is submitted or cancelled meanwhile, if the evaluation
         * that is being published still is the latest.
         *
         * @return {@code true} if it was, and the action ran
         */
        boolean ifLatest(Runnable action);
    }

    private final Function<Q, R> evaluate;
    private final Function<Throwable, R> failure;
    private final BiConsumer<R, Delivery> publish;
    private final Duration debounce;
    private final Duration deadline;
    private final String threadName;
    private final long stackSize;

    private final Object lock = new Object();
    /** The request to evaluate, and its number: each submit or cancel gives a new one. */
    private Q requested;
    private long requestedGeneration;
    private long doneGeneration;
    /** The worker is inside {@code evaluate}: interrupting it cancels the evaluation. */
    private boolean evaluating;
    private Thread worker;

    /**
     * @param evaluate   the evaluation proper, interrupted when it is overtaken or past the deadline
     * @param failure    the result of an evaluation that threw
     * @param publish    hands a result over; it runs after the evaluation, outside its deadline, and may throw (the
     *                   result is then dropped)
     * @param debounce   pause after a request before it is evaluated
     * @param deadline   longest evaluation
     * @param threadName name of the worker thread
     * @param stackSize  stack of the worker thread
     */
    LatestWinsRunner(Function<Q, R> evaluate, Function<Throwable, R> failure, BiConsumer<R, Delivery> publish,
                     Duration debounce, Duration deadline, String threadName, long stackSize) {
        this.evaluate = Objects.requireNonNull(evaluate, "evaluate");
        this.failure = Objects.requireNonNull(failure, "failure");
        this.publish = Objects.requireNonNull(publish, "publish");
        this.debounce = debounce;
        this.deadline = deadline;
        this.threadName = threadName;
        this.stackSize = stackSize;
    }

    /** Asks for {@code request}; the one asked for before, evaluated or not, is overtaken. */
    void submit(Q request) {
        request(Objects.requireNonNull(request, "request"));
    }

    /** Overtakes what was asked for, and stops its evaluation: there is nothing to evaluate any more. */
    void cancel() {
        request(null);
    }

    private void request(Q request) {
        synchronized (lock) {
            requested = request;
            requestedGeneration++;
            if (evaluating) {
                worker.interrupt(); // the running evaluation is out of date
            }
            if (request != null && worker == null) {
                worker = Thread.ofPlatform().name(threadName).daemon().stackSize(stackSize).start(this::work);
            }
            lock.notifyAll();
        }
    }

    /** Evaluates the latest request, after a short pause, then hands its result over. */
    private void work() {
        while (true) {
            Q request;
            long generation;
            try {
                synchronized (lock) {
                    while (requestedGeneration == doneGeneration) {
                        lock.wait();
                    }
                    generation = requestedGeneration;
                    lock.wait(debounce.toMillis());
                    if (generation != requestedGeneration) {
                        continue; // still typing
                    }
                    request = requested;
                    if (request == null) {
                        doneGeneration = generation;
                        continue;
                    }
                    evaluating = true;
                }
            } catch (InterruptedException _) {
                continue;
            }
            R result = evaluateWithinDeadline(request, generation);
            try {
                publish.accept(result, action -> {
                    synchronized (lock) {
                        if (generation != requestedGeneration) {
                            return false;
                        }
                        action.run();
                        return true;
                    }
                });
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "A result could not be handed over", e); // the terminal is closed, for instance
            }
            synchronized (lock) {
                doneGeneration = Math.max(doneGeneration, generation);
            }
        }
    }

    private R evaluateWithinDeadline(Q request, long generation) {
        Thread self = Thread.currentThread();
        Thread deadlineWatch = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(deadline);
                synchronized (lock) {
                    if (evaluating && requestedGeneration == generation) {
                        self.interrupt();
                    }
                }
            } catch (InterruptedException _) {
                // the evaluation finished first
            }
        });
        try {
            return evaluate.apply(request);
        } catch (Throwable t) {
            return failure.apply(t);
        } finally {
            synchronized (lock) {
                evaluating = false;
            }
            deadlineWatch.interrupt();
            Thread.interrupted(); // an interruption that came too late must not reach the publisher
        }
    }
}
