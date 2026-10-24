package io.powerj.fx.model;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import io.powerj.api.Display;
import io.powerj.api.Row;

/**
 * Turns a received object into a {@link Reading}, with the same idea of columns as the table of the terminal (FR-30):
 * <ul>
 *   <li>a record gives one column per component, the ones of {@link Display} first, in its order;</li>
 *   <li>a {@link Row} or any {@link Map} gives one column per key;</li>
 *   <li>a scalar (text, number, date, path…) gives the single column {@value #VALUE};</li>
 *   <li>any other object gives one column per public getter ({@code getX()}, {@code isX()}), by name.</li>
 * </ul>
 * With {@code raw}, the values are shown as they are: a size is a plain number of bytes and a {@link Duration} a plain
 * number of seconds, so both are numbers like any other.
 */
public final class ObjectReader {

    /** Column of an object that has no named values. */
    public static final String VALUE = "value";

    private final PropertyReader reader;
    private final boolean raw;

    /**
     * @param reader reads the components and getters of the objects that are not maps
     * @param raw    sizes and durations are shown as plain numbers, without unit
     */
    public ObjectReader(PropertyReader reader, boolean raw) {
        this.reader = reader;
        this.raw = raw;
    }

    /** A reader that shows sizes and durations with their units. */
    public ObjectReader(PropertyReader reader) {
        this(reader, false);
    }

    public Reading read(Object object) {
        return switch (object) {
            case Row row -> fromMap(object, row, row.sizeColumns());
            case Map<?, ?> map -> fromMap(object, map, java.util.Set.of());
            case Record record -> fromProperties(object, recordColumns(record.getClass()));
            case null -> scalar(null);
            default -> isScalar(object) ? scalar(object) : fromBean(object);
        };
    }

    private Reading scalar(Object object) {
        Object value = shown(object);
        var column = new ColumnSpec(VALUE, ColumnKind.ofValue(value));
        var values = new LinkedHashMap<String, Object>();
        values.put(VALUE, value);
        return new Reading(List.of(column), new GridRow(object, values), Map.of());
    }

    private Reading fromMap(Object source, Map<?, ?> map, java.util.Set<String> sizeColumns) {
        List<ColumnSpec> columns = new ArrayList<>();
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = String.valueOf(entry.getKey());
            Object value = shown(entry.getValue());
            ColumnKind kind = !raw && sizeColumns.contains(name) && ColumnKind.ofValue(value) == ColumnKind.NUMBER
                    ? ColumnKind.SIZE : ColumnKind.ofValue(value);
            columns.add(new ColumnSpec(name, kind));
            values.put(name, value);
        }
        return columns.isEmpty() ? scalar(source) : new Reading(columns, new GridRow(source, values), Map.of());
    }

    private Reading fromProperties(Object source, List<ColumnSpec> columns) {
        if (columns.isEmpty()) {
            return scalar(source);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (ColumnSpec column : columns) {
            try {
                values.put(column.name(), shown(reader.read(source, column.name())));
            } catch (RuntimeException e) {
                values.put(column.name(), null);
                errors.put(column.name(), e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
        return new Reading(columns, new GridRow(source, values), errors);
    }

    private Reading fromBean(Object bean) {
        return fromProperties(bean, beanColumns(bean.getClass()));
    }

    /**
     * Columns of a record type: the ones of {@code @Display} that exist come first, in its order, then every other
     * component, so that the table has all the properties of the object (the terminal shows only the first ones).
     */
    private List<ColumnSpec> recordColumns(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        List<ColumnSpec> columns = new ArrayList<>();
        Display display = type.getAnnotation(Display.class);
        if (display != null) {
            for (String name : display.columns()) {
                Arrays.stream(components).filter(c -> c.getName().equals(name)).findFirst()
                        .ifPresent(c -> columns.add(new ColumnSpec(c.getName(), kind(c))));
            }
        }
        for (RecordComponent component : components) {
            if (columns.stream().noneMatch(c -> c.name().equals(component.getName()))) {
                columns.add(new ColumnSpec(component.getName(), kind(component)));
            }
        }
        return columns;
    }

    /** The kind of a record component, a number if it is shown raw. */
    private ColumnKind kind(RecordComponent component) {
        return raw ? rawKind(ColumnKind.of(component), component.getType()) : ColumnKind.of(component);
    }

    private ColumnKind kind(Method getter) {
        return raw ? rawKind(ColumnKind.of(getter), getter.getReturnType()) : ColumnKind.of(getter);
    }

    private static ColumnKind rawKind(ColumnKind kind, Class<?> type) {
        return kind == ColumnKind.SIZE || type == Duration.class ? ColumnKind.NUMBER : kind;
    }

    /** The value as the table keeps it: a duration is a number of seconds if it is shown raw. */
    private Object shown(Object value) {
        return raw && value instanceof Duration d
                ? BigDecimal.valueOf(d.getSeconds()).add(BigDecimal.valueOf(d.getNano(), 9)).stripTrailingZeros()
                : value;
    }

    /** One column per public getter, by name. */
    private List<ColumnSpec> beanColumns(Class<?> type) {
        Map<String, ColumnSpec> byName = new java.util.TreeMap<>();
        for (Method method : type.getMethods()) {
            String property = property(method);
            if (property != null) {
                byName.putIfAbsent(property, new ColumnSpec(property, kind(method)));
            }
        }
        return List.copyOf(byName.values().stream().sorted(Comparator.comparing(ColumnSpec::name)).toList());
    }

    /** {@code getName()} / {@code isDir()} give {@code name} / {@code dir}; {@code null} for any other method. */
    static String property(Method method) {
        if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())
                || method.getReturnType() == void.class || method.getDeclaringClass() == Object.class) {
            return null;
        }
        String name = method.getName();
        String stem;
        if (name.startsWith("get") && name.length() > 3) {
            stem = name.substring(3);
        } else if (name.startsWith("is") && name.length() > 2
                && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
            stem = name.substring(2);
        } else {
            return null;
        }
        // Introspector rules: URL → URL, Name → name.
        return stem.length() > 1 && Character.isUpperCase(stem.charAt(1)) ? stem
                : stem.substring(0, 1).toLowerCase(Locale.ROOT) + stem.substring(1);
    }

    /** An object shown as one value rather than read property by property. */
    static boolean isScalar(Object object) {
        return object instanceof CharSequence || object instanceof Number || object instanceof Boolean
                || object instanceof Character || object instanceof Enum<?> || object instanceof Temporal
                || object instanceof TemporalAmount || object instanceof Date || object instanceof Path
                || object instanceof java.io.File || object instanceof URI || object instanceof URL
                || object instanceof UUID;
    }
}
