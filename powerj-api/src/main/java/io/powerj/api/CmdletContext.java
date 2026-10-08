package io.powerj.api;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * What the shell makes available to a cmdlet while it runs.
 *
 * @param <O> type of the produced objects
 */
public interface CmdletContext<O> {

    /**
     * Writes an object to the output stream. If the user requested cancellation (Ctrl+C), throws
     * an exception that cleanly stops the cmdlet.
     */
    void emit(O value);

    /** Reports a non-blocking error: the cmdlet continues (depending on the common option {@code --on-error}). */
    void error(String message);

    /** Current directory of the session, base for relative paths. */
    Path currentDirectory();

    /** Environment of the session (modifiable), passed on to native commands launched afterwards. */
    Map<String, String> environment();

    /** Value of a session variable. */
    Optional<Object> variable(String name);

    /** {@code true} if the user requested cancellation (Ctrl+C). */
    boolean cancelled();

    /**
     * Compiles a PowerJ expression into a block, as if it had been typed between braces.
     * E.g. {@code compile("$_.size > 10kb")}.
     *
     * @throws IllegalArgumentException if the expression is syntactically invalid
     */
    ScriptBlock compile(String expression);
}
