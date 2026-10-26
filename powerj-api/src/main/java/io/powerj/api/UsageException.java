package io.powerj.api;

/**
 * A cmdlet was used incorrectly: an option with a value that makes no sense, two options that exclude each other, a
 * missing argument. Thrown from {@link Cmdlet#begin} (or later); the shell shows the message after the name of the
 * command and stops it, without a stack trace.
 * <p>
 * It is an {@link IllegalArgumentException}, which is how usage errors have always been reported, so a cmdlet that
 * throws the standard exception keeps working. This type says it was meant: an {@code IllegalArgumentException} that
 * a library call throws by accident is also shown, but is not a usage error.
 */
public final class UsageException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    /** @param message what the user should change, in the language of the session ({@link Language}) */
    public UsageException(String message) {
        super(message);
    }

    public UsageException(String message, Throwable cause) {
        super(message, cause);
    }
}
