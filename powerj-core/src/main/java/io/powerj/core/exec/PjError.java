package io.powerj.core.exec;

import java.util.Objects;
import java.util.Optional;

/**
 * Error presented to the user: a short message, and possibly the original exception
 * (kept for {@code $errors} and {@code --debug} mode).
 */
public record PjError(String message, Optional<Throwable> cause) {

    public PjError {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(cause, "cause");
    }

    public static PjError of(String message) {
        return new PjError(message, Optional.empty());
    }

    public static PjError of(String message, Throwable cause) {
        return new PjError(message, Optional.of(cause));
    }
}
