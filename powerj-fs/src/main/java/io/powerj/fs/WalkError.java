package io.powerj.fs;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Non-blocking error met during a {@link FileWalker walk}: the walk goes on with the other entries.
 *
 * @param path    file or directory concerned
 * @param kind    nature of the error
 * @param message detail of the error (system message, or message of the visitor's exception)
 */
public record WalkError(Path path, Kind kind, String message) {

    /** Nature of a walk error. */
    public enum Kind {
        /** The path does not exist. */
        NOT_FOUND,
        /** Reading is not allowed. */
        ACCESS_DENIED,
        /** Any other input/output error. */
        IO
    }

    public WalkError {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
        message = message == null ? "" : message;
    }

    /** Error describing an exception raised while reading {@code path}. */
    public static WalkError of(Path path, IOException e) {
        return switch (e) {
            case NoSuchFileException _ -> new WalkError(path, Kind.NOT_FOUND, e.getMessage());
            case AccessDeniedException _ -> new WalkError(path, Kind.ACCESS_DENIED, e.getMessage());
            default -> new WalkError(path, Kind.IO, e.getMessage());
        };
    }
}
