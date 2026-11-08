package io.powerj.api;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * What the shell makes available to a cmdlet while it runs.
 *
 * @param <O> type of the produced objects
 */
public interface CmdletContext<O> {

    /**
     * Writes an object to the output stream. If the user requested cancellation (Ctrl+C), throws
     * an exception that cleanly stops the cmdlet.
     * <p>
     * That exception is a {@link java.util.concurrent.CancellationException}, which is an
     * {@link IllegalStateException}: a cmdlet that catches {@code RuntimeException} or {@code IllegalStateException}
     * around {@code emit}, {@link ScriptBlock#invoke} or anything that checks {@link #cancelled()} must let it through
     * ({@code catch (CancellationException e) { throw e; }} first), or Ctrl+C is taken for an error and the cmdlet goes on.
     */
    void emit(O value);

    /** Reports a non-blocking error: the cmdlet continues (depending on the common option {@code --on-error}). */
    void error(String message);

    /** Current directory of the session, base for relative paths. */
    Path currentDirectory();

    /**
     * Environment of the session, as it is when this is called: a read-only copy that does not follow later changes (the
     * other stages of the pipeline and the commands launched afterwards change the real one). To change the
     * environment, use {@link #setEnvironment} and {@link #unsetEnvironment}.
     */
    Map<String, String> environment();

    /**
     * Sets an environment variable of the session, passed on to the native commands launched afterwards.
     *
     * @throws UnsupportedOperationException by default: the shell's context overrides it
     */
    default void setEnvironment(String name, String value) {
        throw new UnsupportedOperationException("this context cannot change the environment");
    }

    /**
     * Removes an environment variable of the session.
     *
     * @return {@code true} if the variable was set
     * @throws UnsupportedOperationException by default: the shell's context overrides it
     */
    default boolean unsetEnvironment(String name) {
        throw new UnsupportedOperationException("this context cannot change the environment");
    }

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
     * The state of this execution of the cmdlet: the object that {@code initial} made the first time it was asked for
     * a {@code type}, and the same one on every later call, whichever of {@link Cmdlet#begin}, {@link Cmdlet#process}
     * or {@link Cmdlet#end} asks. A counter, a list to sort, an open file: whatever belongs to one run.
     * <p>
     * A cmdlet is one object shared by every command that uses it, even by two stages of the same pipeline, so what
     * belongs to one run must not be kept in a field of the cmdlet. Keeping it here makes each run independent, with no
     * table to maintain and nothing to release in {@code end}.
     * <p>
     * If the state implements {@link AutoCloseable}, it is closed when the run is over, however it ended: after
     * {@code end}, after an error, after a cancellation (Ctrl+C). {@code end} itself is only called after the last
     * object was received: use the state for what must be released in every case, and {@code end} for what is emitted.
     * States are closed in the reverse order of their creation.
     *
     * @param type    the kind of state; a cmdlet with several independent states uses one class for each
     * @param initial makes the state the first time; it may use the parameters and the context
     * @throws UnsupportedOperationException by default: the shell's context overrides it; a context written for a test
     *                                       must do the same if the cmdlet under test keeps a state
     */
    default <S> S state(Class<S> type, Supplier<? extends S> initial) {
        throw new UnsupportedOperationException("this context keeps no execution state");
    }

    /**
     * Compiles a PowerJ expression into a block, as if it had been typed between braces.
     * E.g. {@code compile("$_.size > 10kb")}.
     *
     * @throws IllegalArgumentException if the expression is syntactically invalid
     */
    ScriptBlock compile(String expression);

    /**
     * The value of the property {@code name} of {@code target}: the way to read an attribute of an object that comes from
     * another module. Every command reads the properties of the objects it receives through this method, whichever
     * module the command or the object comes from: the engine reads for them, because the module system gives it alone the
     * access to the records of every module.
     * <p>
     * It finds what {@code $_.name} finds for {@code $_ = target}, in the same order: a record component, a getter
     * {@code getName()} / {@code isName()}, a public field, a key of a {@link java.util.Map} (a {@link Row} included),
     * with the same guard rails in preview mode. {@code name} is <b>one literal name</b>, never an expression or a path (a
     * key {@code "first name"} or {@code "a.b"} is read as it is), and <b>the case of the name matters</b>: {@code size} is
     * not {@code Size}. For a getter, the first letter is free, as for a JavaBean: {@code name} reads {@code getName()}. A
     * path such as {@code owner.name}, typed by a user, is read one name after the other.
     *
     * @param target the object to read, not {@code null}
     * @param name   the name of the property, for example {@code "pid"}
     * @return the value, possibly {@code null}
     * @throws RuntimeException if the object has no such property or if reading it fails; the message tells the user
     *                          what is missing, and the name in another case if there is one.
     *                          {@link java.util.concurrent.CancellationException} is never wrapped.
     */
    Object readProperty(Object target, String name);

    /**
     * {@link #readProperty} that returns a failure as a value instead of throwing it, as {@link ScriptBlock#tryInvoke}
     * does: for a command that skips the objects it cannot read and goes on. {@link java.util.concurrent.CancellationException}
     * is still thrown, so that Ctrl+C is never taken for an unreadable object.
     */
    default Attempt<Object> tryReadProperty(Object target, String name) {
        try {
            return Attempt.succeeded(readProperty(target, name));
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (RuntimeException e) {
            return Attempt.failed(e);
        }
    }

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
     * A service that the shell offers for what only a few cmdlets need, so that the contract of every cmdlet does not
     * grow with it ({@code context.service(PerfReports.class)}, in {@code io.powerj.api.perf}).
     *
     * @return the service, or empty if this shell does not offer it
     */
    default <S> Optional<S> service(Class<S> type) {
        return Optional.empty();
    }
}
