package io.powerj.cmdlets;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Named values of an object, for {@code to-json} and {@code to-csv} (specification FR-73): the components of a
 * record, the entries of a map ({@code Row} included), or a single column {@code value} for anything else.
 */
final class Properties {

    /** Column of an object that has no named values. */
    static final String VALUE = "value";

    private Properties() {
    }

    /**
     * Components of a record, in declaration order.
     *
     * @throws IllegalStateException if a component cannot be read
     */
    static Map<String, Object> of(Record record) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            values.put(component.getName(), read(record, component));
        }
        return values;
    }

    /** Names of the columns of an object. */
    static List<String> columns(Object value) {
        List<String> columns = new ArrayList<>();
        switch (value) {
            case Record record -> {
                for (RecordComponent component : record.getClass().getRecordComponents()) {
                    columns.add(component.getName());
                }
            }
            case Map<?, ?> map -> map.keySet().forEach(key -> columns.add(String.valueOf(key)));
            case null, default -> columns.add(VALUE);
        }
        return columns;
    }

    /**
     * Value of a column of an object, {@code null} if it has none.
     *
     * @throws IllegalStateException if a record component cannot be read
     */
    static Object get(Object value, String column) {
        switch (value) {
            case Record record -> {
                for (RecordComponent component : record.getClass().getRecordComponents()) {
                    if (component.getName().equals(column)) {
                        return read(record, component);
                    }
                }
                return null;
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

    private static Object read(Record record, RecordComponent component) {
        try {
            var accessor = component.getAccessor();
            accessor.trySetAccessible();
            return accessor.invoke(record);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(Messages.get("properties.read.failed", component.getName(),
                    e.getCause()), e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(Messages.get("properties.read.failed", component.getName(), e), e);
        }
    }
}
