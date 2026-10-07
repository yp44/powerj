package io.powerj.core.exec;

import java.util.Objects;
import java.util.Optional;

/**
 * Erreur présentée à l'utilisateur : un message court, et éventuellement l'exception d'origine
 * (conservée pour {@code $errors} et le mode {@code --debug}).
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
