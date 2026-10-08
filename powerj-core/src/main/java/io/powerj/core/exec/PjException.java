package io.powerj.core.exec;

import java.util.Objects;

/** Expected blocking error, thrown by the shell or a cmdlet; its message is intended for the user. */
public class PjException extends RuntimeException {

    private final PjError error;

    public PjException(String message) {
        this(PjError.of(message));
    }

    public PjException(PjError error) {
        super(error.message(), error.cause().orElse(null));
        this.error = Objects.requireNonNull(error, "error");
    }

    public PjError error() {
        return error;
    }
}
