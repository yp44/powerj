package io.powerj.core.java;

import io.powerj.api.ScriptBlock;

/**
 * A block that takes any number of arguments: the lambda {@code (a, b) -> …} and the method reference
 * {@code String::concat}. {@link ScriptBlock#invoke(Object)} passes one, {@link ScriptBlock#invoke(Object, Object,
 * Object...)} two or more.
 */
public interface Applicable extends ScriptBlock {

    /** Runs the block with these arguments. */
    Object apply(Object[] args);

    @Override
    default Object invoke(Object first, Object second, Object... more) {
        Object[] args = new Object[2 + more.length];
        args[0] = first;
        args[1] = second;
        System.arraycopy(more, 0, args, 2, more.length);
        return apply(args);
    }
}
