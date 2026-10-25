package io.powerj.core.exec;

import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The variables the user defines ({@code $x = 1}). A flat map for now, safe to read from the stages of a pipeline while
 * the command thread writes; a variable may hold {@code null}. The automatic variables ({@code $?}, {@code $pwd}…) are
 * not here: see {@link AutomaticVariable}.
 */
final class Variables {

    /** Stands for {@code null} in {@link #values}, which does not hold null values. */
    private static final Object NULL = new Object();

    private final Map<String, Object> values = new ConcurrentHashMap<>();

    void set(String name, Object value) {
        values.put(name, value == null ? NULL : value);
    }

    /** Names of the defined variables, sorted. */
    SortedSet<String> names() {
        return new TreeSet<>(values.keySet());
    }

    /** Value of a defined variable (which may be {@code null}); an unknown name is an error. */
    Object get(String name) {
        Object value = values.get(name);
        if (value == null) {
            throw new PjException(Messages.get("variable.unknown", name));
        }
        return value == NULL ? null : value;
    }

    /** {@code $debug}: show the Java stack trace of errors (FR-43). */
    boolean debug() {
        return Boolean.TRUE.equals(values.get("debug"));
    }
}
