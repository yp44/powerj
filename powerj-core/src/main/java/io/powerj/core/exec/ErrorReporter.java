package io.powerj.core.exec;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Where the errors of a line go: the terminal, the file of a {@code 2>} redirection, or the preview that collects them
 * (FR-66). It also remembers whether the line hit a blocking error, which stops a script (FR-04d).
 */
final class ErrorReporter {

    private final Session session;
    private final ShellIo io;
    /** A blocking error occurred during the last line (non-interactive mode: the script stops). */
    private volatile boolean blocking;

    ErrorReporter(Session session, ShellIo io) {
        this.session = session;
        this.io = io;
    }

    /** A new line begins: its errors are counted from zero. */
    void reset() {
        blocking = false;
    }

    /** The line hit a blocking error (an error, not just a failed command). */
    void blocking() {
        blocking = true;
    }

    /** {@code true} if the last executed line hit a blocking error. */
    boolean hadBlocking() {
        return blocking;
    }

    /**
     * Message of a blocking error; the original Java exception is kept in {@code $errors} and its
     * stack trace appended to the message if {@code $debug} is {@code true} (FR-43, FR-53).
     */
    String describe(PjError error) {
        if (error.cause().isEmpty()) {
            return error.message();
        }
        Throwable cause = error.cause().get();
        if (PreviewGuard.active()) {
            return error.message(); // $errors only keeps the errors of executed commands
        }
        session.recordError(cause);
        if (!session.debug()) {
            return error.message();
        }
        var trace = new StringWriter();
        cause.printStackTrace(new PrintWriter(trace));
        return error.message() + System.lineSeparator() + trace.toString().stripTrailing();
    }

    /** Receives the non-blocking errors of a pipeline: the {@code 2>} file, the preview, or the terminal. */
    Consumer<String> sink(Optional<NativeRunner.FileTarget> errTarget) {
        PreviewGuard guard = PreviewGuard.current();
        if (guard != null) {
            return guard::error; // nothing is written while the user types (FR-66)
        }
        return errTarget.<Consumer<String>>map(target -> message -> append(target.file(), message))
                .orElse(io.errors());
    }

    /** Shows a blocking error where the statement's errors go. */
    void report(String message, Optional<NativeRunner.FileTarget> target) {
        if (target.isPresent()) {
            write(target.get(), message);
        } else {
            io.errors().accept(message);
        }
    }

    private static void write(NativeRunner.FileTarget target, String message) {
        if (target.append()) {
            append(target.file(), message);
        } else {
            try {
                Files.writeString(target.file(), message + System.lineSeparator(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new PjException(PjError.of(Messages.get("file.writeFailed", target.file(), e.getMessage()), e));
            }
        }
    }

    private static void append(Path file, String line) {
        try {
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new PjException(PjError.of(Messages.get("file.writeFailed", file, e.getMessage()), e));
        }
    }
}
