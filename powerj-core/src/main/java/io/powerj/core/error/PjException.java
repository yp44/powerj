package io.powerj.core.error;

import java.util.Optional;

/** Expected blocking error, thrown by the shell or a cmdlet; its message is intended for the user. */
public class PjException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PjException(String message) {
        this(PjError.of(message));
    }

    public PjException(PjError error) {
        super(error.message(), error.cause().orElse(null));
    }

    /**
     * The error to present: rebuilt from the message and the cause that this exception carries, so that it holds no
     * field of a type that is not serializable ({@link PjError} keeps its cause in an {@link Optional}).
     */
    public PjError error() {
        return new PjError(getMessage(), Optional.ofNullable(getCause()));
    }
}
