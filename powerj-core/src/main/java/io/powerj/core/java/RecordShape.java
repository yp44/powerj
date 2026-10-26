package io.powerj.core.java;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Map;
import java.util.TreeMap;

/**
 * The accessors of a record class, looked up once per class and not at every read (review C-26).
 * {@code Class.getRecordComponents()} builds new objects at each call, about 7 µs, and a pipeline reads one property of
 * each of its objects: {@code ls -r | where { $_.size > 1mb }} spent seconds in reflection per million files.
 */
final class RecordShape {

    private static final ClassValue<RecordShape> SHAPES = new ClassValue<>() {
        @Override
        protected RecordShape computeValue(Class<?> type) {
            return new RecordShape(type);
        }
    };

    /** Accessor of each component by name, whatever the case; the first declared wins if two names differ by case. */
    private final Map<String, Method> accessors = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    private RecordShape(Class<?> type) {
        for (RecordComponent component : type.getRecordComponents()) {
            Method accessor = component.getAccessor();
            accessor.trySetAccessible();
            accessors.putIfAbsent(component.getName(), accessor);
        }
    }

    /** The shape of a record class; the same one every time. */
    static RecordShape of(Class<?> recordType) {
        return SHAPES.get(recordType);
    }

    /** The accessor of the component called {@code name} (any case), or {@code null} if there is none. */
    Method accessor(String name) {
        return accessors.get(name);
    }
}
