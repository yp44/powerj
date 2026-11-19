package io.powerj.core.java;

import java.nio.channels.ClosedByInterruptException;
import java.util.concurrent.CancellationException;

/**
 * Ctrl+C reaching a Java method (FR-03, FR-57, audit v2 R-01). A blocking JDK call ({@code Thread.sleep},
 * {@code BlockingQueue.take}, {@code Process.waitFor}…) throws {@link InterruptedException} <b>and clears the interrupt
 * flag</b>; a channel throws {@link ClosedByInterruptException}. Left as an ordinary error, the interruption would let
 * the next statement of the line run.
 */
final class Cancellation {

    private Cancellation() { }

    /** {@code true} if {@code cause} is what a blocking call throws when its thread is interrupted. */
    static boolean isInterruption(Throwable cause) {
        return cause instanceof InterruptedException || cause instanceof ClosedByInterruptException;
    }

    /** Sets the interrupt flag again (the JDK cleared it) and returns the exception that stops the command. */
    static CancellationException cancelled() {
        Thread.currentThread().interrupt();
        return new CancellationException();
    }
}
