package io.powerj.fx.model;

/**
 * Reads a property of an object by its exact name, the way the engine does for every command
 * ({@code CmdletContext.readProperty}): the model does not reflect on the objects it shows.
 */
@FunctionalInterface
public interface PropertyReader {

    /**
     * @throws RuntimeException if the property cannot be read
     */
    Object read(Object target, String name);
}
