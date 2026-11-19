package io.powerj.fs;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.concurrent.CancellationException;

/**
 * Tells an interruption from a failure of a read. A file read through {@code Files.newInputStream} is backed by an
 * interruptible channel: when Ctrl+C interrupts the thread, the read throws {@link ClosedByInterruptException}, an
 * {@link IOException} with no message. Reported as an error, it showed as {@code cannot read x: null} while the command
 * went on with the next file (audit v2, R-11); it is a cancellation.
 */
public final class Interruption {

    private Interruption() {
    }

    /**
     * Turns the {@link IOException} of an interrupted read into a cancellation: the flag of the thread is set again and
     * {@link CancellationException} is thrown. Any other {@link IOException} is left alone, to be reported.
     *
     * @throws CancellationException if {@code e} is a {@link ClosedByInterruptException} or an
     *                               {@link InterruptedIOException}
     */
    public static void rethrow(IOException e) {
        if (is(e)) {
            Thread.currentThread().interrupt();
            throw new CancellationException();
        }
    }

    /** Is this {@link IOException} the consequence of an interruption of the thread that was reading? */
    public static boolean is(IOException e) {
        return e instanceof ClosedByInterruptException || e instanceof InterruptedIOException;
    }
}
