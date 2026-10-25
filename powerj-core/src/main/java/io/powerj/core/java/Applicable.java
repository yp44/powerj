package io.powerj.core.java;

import io.powerj.api.ScriptBlock;

/**
 * A block that takes any number of arguments: the lambda {@code (a, b) -> …} and the method reference
 * {@code String::concat}. {@link ScriptBlock#invoke} passes one.
 */
public interface Applicable extends ScriptBlock {

    /** Runs the block with these arguments. */
    Object apply(Object[] args);
}
