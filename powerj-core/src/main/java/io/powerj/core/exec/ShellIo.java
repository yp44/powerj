package io.powerj.core.exec;

import java.io.PrintWriter;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Shell outputs as seen by the interpreter.
 *
 * @param out         output stream (displayed values)
 * @param errors      PowerJ error stream: one line per message (displayed in red by the terminal)
 * @param width       terminal width in characters, for tables
 * @param interactive {@code true} if a real terminal is attached: native commands at the end of the line
 *                    then inherit the console directly (colors, interactive programs)
 */
public record ShellIo(PrintWriter out, Consumer<String> errors, boolean interactive, IntSupplier width) {

    public ShellIo {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(errors, "errors");
        Objects.requireNonNull(width, "width");
    }

    /** Outputs without a real terminal: fixed width of 120 columns. */
    public ShellIo(PrintWriter out, Consumer<String> errors, boolean interactive) {
        this(out, errors, interactive, () -> 120);
    }
}
