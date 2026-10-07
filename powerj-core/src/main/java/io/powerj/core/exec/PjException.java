package io.powerj.core.exec;

import java.util.Objects;

/** Erreur bloquante prévue, levée par le shell ou un cmdlet ; son message est destiné à l'utilisateur. */
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
