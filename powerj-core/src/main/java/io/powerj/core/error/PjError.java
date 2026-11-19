package io.powerj.core.error;

import java.util.Objects;
import java.util.Optional;

/**
 * Error presented to the user: a short message, possibly the original exception (kept for {@code $errors} and
 * {@code --debug} mode), and possibly the place in the line that it is about.
 *
 * @param offset index in the line (UTF-16 units) of what the error is about, or -1: the shell shows the line with a
 *               {@code ^} under it ({@link ErrorLocation})
 */
public record PjError(String message, Optional<Throwable> cause, int offset) {

    public PjError {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(cause, "cause");
    }

    public PjError(String message, Optional<Throwable> cause) {
        this(message, cause, -1);
    }

    public static PjError of(String message) {
        return new PjError(message, Optional.empty());
    }

    public static PjError of(String message, Throwable cause) {
        return new PjError(message, Optional.of(cause));
    }

    /** The same error, about the character at {@code offset} of the line. */
    public PjError at(int offset) {
        return new PjError(message, cause, offset);
    }
}
