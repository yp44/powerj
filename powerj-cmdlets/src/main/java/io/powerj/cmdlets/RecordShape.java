package io.powerj.cmdlets;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.powerj.api.Bytes;

/**
 * The components of a record class, read once per class and not at every use (review C-26):
 * {@code Class.getRecordComponents()} builds new objects at each call, about 7 µs, and {@code to-csv}, {@code to-json}
 * and {@code join} ask for every column of every object.
 */
final class RecordShape {

    private static final ClassValue<RecordShape> SHAPES = new ClassValue<>() {
        @Override
        protected RecordShape computeValue(Class<?> type) {
            return new RecordShape(type);
        }
    };

    private final List<String> names;
    private final Method[] accessors;
    private final Map<String, Integer> indexes = new HashMap<>();
    private final Set<String> bytes = new HashSet<>();

    private RecordShape(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        String[] names = new String[components.length];
        accessors = new Method[components.length];
        for (int i = 0; i < components.length; i++) {
            names[i] = components[i].getName();
            accessors[i] = components[i].getAccessor();
            accessors[i].trySetAccessible();
            indexes.put(names[i], i); // the names of the components of a record are all different
            if (components[i].isAnnotationPresent(Bytes.class)) {
                bytes.add(names[i]);
            }
        }
        this.names = List.of(names);
    }

    /** The shape of a record class; the same one every time. */
    static RecordShape of(Class<?> recordType) {
        return SHAPES.get(recordType);
    }

    /** Names of the components, in declaration order (unmodifiable). */
    List<String> names() {
        return names;
    }

    /** Position of the component called {@code name}, or -1. */
    int indexOf(String name) {
        return indexes.getOrDefault(name, -1);
    }

    /** Accessor of the component at {@code index}, already made accessible when it can be. */
    Method accessor(int index) {
        return accessors[index];
    }

    /** {@code true} if the component called {@code name} is a size ({@link Bytes}). */
    boolean isBytes(String name) {
        return bytes.contains(name);
    }
}
