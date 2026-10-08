package io.powerj.core.lang;

/** Access applied to a value: property {@code .nom} or index {@code [n]}. */
public sealed interface Accessor {

    record Property(String name) implements Accessor { }

    /** Index; negative = from the end ({@code [-1]} is the last element). */
    record Index(int index) implements Accessor { }
}
