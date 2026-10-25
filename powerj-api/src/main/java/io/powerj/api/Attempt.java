package io.powerj.api;

import java.util.function.Function;

/**
 * The result of evaluating a block for one object ({@link ScriptBlock#tryInvoke}): the value, or the error that this
 * object caused. The usual reaction to an error is non-blocking: report it with {@link CmdletContext#error} and go on
 * with the next object.
 * <p>
 * A cancellation (Ctrl+C) is never a result: it is thrown, because it is not the fault of the object.
 *
 * @param value the value of the block, {@code null} if it returned {@code null} or if it failed
 * @param error the error, {@code null} if the block succeeded
 * @param <T>   type of the value
 */
public record Attempt<T>(T value, RuntimeException error) {

    public Attempt {
        if (value != null && error != null) {
            throw new IllegalArgumentException("an attempt has a value or an error, not both");
        }
    }

    public static <T> Attempt<T> succeeded(T value) {
        return new Attempt<>(value, null);
    }

    public static <T> Attempt<T> failed(RuntimeException error) {
        return new Attempt<>(null, java.util.Objects.requireNonNull(error));
    }

    /** {@code true} if the block failed for this object. */
    public boolean failed() {
        return error != null;
    }

    /** The same attempt with {@code mapper} applied to the value; a failure stays as it is. */
    public <R> Attempt<R> map(Function<? super T, ? extends R> mapper) {
        return failed() ? failed(error) : succeeded(mapper.apply(value));
    }
}
