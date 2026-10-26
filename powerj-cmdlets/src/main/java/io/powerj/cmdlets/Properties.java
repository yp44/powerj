package io.powerj.cmdlets;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * Named values of an object, for {@code to-json} and {@code to-csv} (specification FR-73): the components of a
 * record, the entries of a map ({@code Row} included), or a single column {@code value} for anything else.
 */
final class Properties {

    /** Column of an object that has no named values. */
    static final String VALUE = "value";

    /**
     * A component of a record that cannot be read. A dedicated type, so that a caller never has to catch
     * {@link IllegalStateException}, which is also what a {@link CancellationException} is (review C-15): to-json once
     * took Ctrl+C for an object it could not write.
     */
    static final class Unreadable extends IllegalStateException {

        Unreadable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private Properties() {
    }

    /**
     * Components of a record, in declaration order.
     *
     * @throws IllegalStateException if a component cannot be read
     */
    static Map<String, Object> of(Record record) {
        RecordShape shape = RecordShape.of(record.getClass());
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < shape.names().size(); i++) {
            values.put(shape.names().get(i), read(record, shape, i));
        }
        return values;
    }

    /** Names of the columns of an object. */
    static List<String> columns(Object value) {
        List<String> columns = new ArrayList<>();
        switch (value) {
            case Record record -> columns.addAll(RecordShape.of(record.getClass()).names());
            case Map<?, ?> map -> map.keySet().forEach(key -> columns.add(String.valueOf(key)));
            case null, default -> columns.add(VALUE);
        }
        return columns;
    }

    /**
     * Value of a column of an object, {@code null} if it has none.
     *
     * @throws Unreadable if a record component cannot be read
     */
    static Object get(Object value, String column) {
        switch (value) {
            case Record record -> {
                RecordShape shape = RecordShape.of(record.getClass());
                int index = shape.indexOf(column);
                return index < 0 ? null : read(record, shape, index);
            }
            case Map<?, ?> map -> {
                if (map.containsKey(column)) {
                    return map.get(column);
                }
                for (Map.Entry<?, ?> entry : map.entrySet()) { // keys that are not strings
                    if (String.valueOf(entry.getKey()).equals(column)) {
                        return entry.getValue();
                    }
                }
                return null;
            }
            case null, default -> {
                return VALUE.equals(column) ? value : null;
            }
        }
    }

    private static Object read(Record record, RecordShape shape, int index) {
        String name = shape.names().get(index);
        try {
            return shape.accessor(index).invoke(record);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof CancellationException cancelled) {
                throw cancelled; // an accessor that was interrupted: the command is being stopped
            }
            throw new Unreadable(Messages.get("properties.read.failed", name, e.getCause()), e.getCause());
        } catch (IllegalAccessException e) {
            throw new Unreadable(Messages.get("properties.read.failed", name, e), e);
        }
    }
}
