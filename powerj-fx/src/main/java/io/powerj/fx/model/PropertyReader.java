package io.powerj.fx.model;

/** Reads a property of an object, the way {@code $_.name} does in the shell. */
@FunctionalInterface
public interface PropertyReader {

    /**
     * @throws RuntimeException if the property cannot be read
     */
    Object read(Object target, String name);
}
