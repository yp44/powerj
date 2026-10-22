package io.powerj.api;

import java.nio.file.Path;
import java.util.List;
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

    /**
     * {@code true} if the cmdlet receives objects from the pipeline ({@code … | cmdlet}), even if none
     * arrives; {@code false} at the head of a pipeline. Known as soon as {@code begin}.
     */
    default boolean hasInput() {
        return false;
    }

    /** {@code true} if the user requested cancellation (Ctrl+C). */
    boolean cancelled();

    /**
     * Compiles a PowerJ expression into a block, as if it had been typed between braces.
     * E.g. {@code compile("$_.size > 10kb")}.
     *
     * @throws IllegalArgumentException if the expression is syntactically invalid
     */
    ScriptBlock compile(String expression);

    /**
     * The cmdlet will read no more objects (specification FR-68b): {@link Cmdlet#process} is no longer called,
     * {@link Cmdlet#end} is called, and the previous stage of the pipeline is stopped (a cmdlet is interrupted, a
     * native command terminated) without any error. No effect at the head of a pipeline.
     */
    default void stopInput() {
    }

    /**
     * PowerJ configuration folder ({@code ~/.powerj}, or {@code POWERJ_HOME}; specification §8), where a cmdlet can
     * read its settings. Empty when the shell runs without one.
     */
    default Optional<Path> configDirectory() {
        return Optional.empty();
    }

    /**
     * The lines measured in perf mode (specification FR-76), most recent first; empty when none was measured.
     */
    default List<PerfReport> perfReports() {
        return List.of();
    }
}
