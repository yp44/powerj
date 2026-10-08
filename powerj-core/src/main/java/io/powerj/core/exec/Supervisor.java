package io.powerj.core.exec;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs a command while catching any {@link Throwable} (specification FR-56), so that nothing a
 * line executes can bring down the shell.
 */
public final class Supervisor {

    private static final Logger LOG = Logger.getLogger(Supervisor.class.getName());

    /** Reserve released on {@link OutOfMemoryError} to leave the shell enough to keep going. */
    private static final int MEMORY_RESERVE_BYTES = 8 * 1024 * 1024;

    /** Work executed under supervision. */
    @FunctionalInterface
    public interface Task {
        List<Object> execute() throws Exception;
    }

    /** Stack of the execution threads: headroom for deeply nested expressions. */
    private static final long STACK_SIZE = 16L * 1024 * 1024;

    /** Running command: its thread, its result, and the number of Ctrl+C received. */
    private record Running(Thread worker, CompletableFuture<Outcome> outcome, String commandLine, AtomicInteger cancels) { }

    private byte[] memoryReserve = new byte[MEMORY_RESERVE_BYTES];
    private volatile Running running;

    /**
     * Runs the task in a dedicated thread and returns its result; never throws an exception. If the task
     * does not react to the first Ctrl+C, the second one abandons it (FR-57): the result is
     * {@link Outcome.Abandoned} and the task keeps running in the background, without holding up the shell.
     *
     * @param commandLine line entered, for the log and messages
     */
    public Outcome run(String commandLine, Task task) {
        var outcome = new CompletableFuture<Outcome>();
        Thread worker = Thread.ofPlatform().name("powerj-commande").daemon().stackSize(STACK_SIZE).unstarted(() -> {
            Outcome result;
            try {
                result = new Outcome.Success(task.execute());
            } catch (Throwable t) {
                result = failureOf(commandLine, t);
            } finally {
                restoreMemoryReserve();
            }
            outcome.complete(result);
        });
        running = new Running(worker, outcome, commandLine, new AtomicInteger());
        try {
            worker.start();
            return outcome.join();
        } finally {
            running = null;
        }
    }

    /**
     * Ctrl+C: the first one requests cancellation of the running command (interruption); the next one
     * abandons it if it has not stopped. No effect if no command is running.
     */
    public void cancel() {
        var current = running;
        if (current == null) {
            return;
        }
        if (current.cancels().incrementAndGet() == 1) {
            current.worker().interrupt();
        } else {
            LOG.warning(() -> "Command abandoned: " + current.commandLine());
            current.outcome().complete(new Outcome.Abandoned(current.commandLine()));
        }
    }

    private Outcome failureOf(String commandLine, Throwable t) {
        return switch (t) {
            case InterruptedException _, CancellationException _ -> new Outcome.Cancelled();
            case PjException e -> new Outcome.Failure(e.error());
            case OutOfMemoryError e -> {
                memoryReserve = null;
                yield new Outcome.Failure(PjError.of(Messages.get("error.outOfMemory"), e));
            }
            case StackOverflowError e -> new Outcome.Failure(PjError.of(Messages.get("error.stackOverflow"), e));
            case LinkageError e -> new Outcome.Failure(PjError.of(Messages.get("error.linkage", e.getMessage()), e));
            default -> {
                LOG.log(Level.SEVERE, "Internal error while running: " + commandLine, t);
                yield new Outcome.Failure(PjError.of(Messages.get("error.internal", t), t));
            }
        };
    }

    private void restoreMemoryReserve() {
        if (memoryReserve == null) {
            try {
                memoryReserve = new byte[MEMORY_RESERVE_BYTES];
            } catch (OutOfMemoryError _) {
                // Still no memory: we will retry after the next command.
            }
        }
    }
}
