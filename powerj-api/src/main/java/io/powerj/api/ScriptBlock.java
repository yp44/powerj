package io.powerj.api;

/**
 * Expression block typed by the user, evaluated for a received object (specification FR-33b, FR-36):
 * lambda {@code { f -> f.size > 1mb && !f.dir }}, {@code $_} block {@code { $_.dir }}, or method
 * reference {@code FileEntry::name}.
 */
public interface ScriptBlock {

    /**
     * Evaluates the block with {@code $_} equal to {@code current}.
     * <p>
     * To go on with the next object when the block fails for this one, use {@link #tryInvoke}: it cannot take a
     * cancellation for a failure.
     *
     * @throws java.util.concurrent.CancellationException if the user requested cancellation (Ctrl+C); it is an
     *         {@link IllegalStateException}, so a caller that catches {@code RuntimeException} must rethrow it first
     */
    Object invoke(Object current);

    default Object invoke() {
        return invoke(null);
    }

    /**
     * Evaluates the block with two arguments or more, one per parameter of a lambda {@code { (a, b) -> … }} or of a
     * method reference ({@code String::concat}). The first two are separate parameters so that {@link #invoke(Object)}
     * stays the method of a single argument, {@code null} or an array included.
     * <p>
     * This default is for a block of one argument: it refuses more. The blocks of the shell take as many as they declare.
     *
     * @throws IllegalArgumentException if the block does not take that number of arguments
     * @throws java.util.concurrent.CancellationException if the user requested cancellation (Ctrl+C)
     */
    default Object invoke(Object first, Object second, Object... more) {
        throw new IllegalArgumentException(Messages.get("block.tooManyArguments", 2 + more.length));
    }

    /**
     * Evaluates the block as {@link #invoke} does, but an error of the evaluation is the result and not an exception:
     * the cmdlet reports it and goes on with the next object (FR-36). A cancellation (Ctrl+C) is still thrown.
     */
    default Attempt<Object> tryInvoke(Object current) {
        try {
            return Attempt.succeeded(invoke(current));
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (RuntimeException e) {
            return Attempt.failed(e);
        }
    }

    /**
     * Evaluates the block as {@link #test} does, with the error of the evaluation, or a result that is not a boolean,
     * as the result and not as an exception. A cancellation (Ctrl+C) is still thrown.
     */
    default Attempt<Boolean> tryTest(Object current) {
        try {
            return Attempt.succeeded(test(current));
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (RuntimeException e) {
            return Attempt.failed(e);
        }
    }

    /** Source text of the block, without the braces. */
    String source();

    /**
     * Evaluates the block as a condition (FR-33b): the result must be a boolean, as for a Java
     * {@code Predicate}.
     *
     * @throws IllegalStateException if the block returns something other than a boolean
     */
    default boolean test(Object current) {
        Object value = invoke(current);
        if (value instanceof Boolean b) {
            return b;
        }
        throw new IllegalStateException(Messages.get("block.notBoolean",
                value == null ? "null" : value.getClass().getSimpleName() + " " + value));
    }
}
