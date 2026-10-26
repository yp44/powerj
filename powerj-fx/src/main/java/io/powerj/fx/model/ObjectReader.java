package io.powerj.fx.model;

import java.io.File;
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
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;

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

    /** Most shapes kept for objects read as maps: a pipeline of maps whose keys never repeat must not fill the memory. */
    private static final int MAX_MAP_SHAPES = 256;

    /** What is known of a type once and for all: its columns, and the shape of the rows read from it. */
    private record Layout(List<ColumnSpec> columns, RowShape shape) {

        Layout(List<ColumnSpec> columns) {
            this(List.copyOf(columns), RowShape.of(columns.stream().map(ColumnSpec::name).toList()));
        }
    }

    private final PropertyReader reader;
    private final boolean raw;
    private final boolean keepSource;
    private final Map<Class<?>, Layout> layouts = new ConcurrentHashMap<>();
    private final Map<List<String>, RowShape> mapShapes = new ConcurrentHashMap<>();

    /**
     * @param reader     reads the components and getters of the objects that are not maps
     * @param raw        sizes and durations are shown as plain numbers, without unit
     * @param keepSource whether a row keeps the object it was read from; only {@code --pass-thru} gives it back, and a row
     *                   that keeps it also keeps everything it refers to
     */
    public ObjectReader(PropertyReader reader, boolean raw, boolean keepSource) {
        this.reader = reader;
        this.raw = raw;
        this.keepSource = keepSource;
    }

    public ObjectReader(PropertyReader reader, boolean raw) {
        this(reader, raw, true);
    }

    /** A reader that shows sizes and durations with their units. */
    public ObjectReader(PropertyReader reader) {
        this(reader, false);
    }

    /** How many shapes of maps are kept (a test of the bound). */
    int mapShapesKept() {
        return mapShapes.size();
    }

    public Reading read(Object object) {
        return switch (object) {
            case Row row -> fromMap(object, row, row.sizeColumns());
            case Map<?, ?> map -> fromMap(object, map, Set.of());
            case Record record -> fromProperties(object, layouts.computeIfAbsent(record.getClass(),
                    type -> new Layout(recordColumns(type))));
            case null -> scalar(null);
            default -> isScalar(object) ? scalar(object) : fromBean(object);
        };
    }

    private static final RowShape SCALAR = RowShape.of(List.of(VALUE));

    private Object kept(Object source) {
        return keepSource ? source : null;
    }

    private Reading scalar(Object object) {
        Object value = shown(object);
        var column = new ColumnSpec(VALUE, ColumnKind.ofValue(value));
        return new Reading(List.of(column), new GridRow(kept(object), SCALAR, new Object[] {value}, 0), Map.of());
    }

    private Reading fromMap(Object source, Map<?, ?> map, Set<String> sizeColumns) {
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
        if (columns.isEmpty()) {
            return scalar(source);
        }
        List<String> names = List.copyOf(values.keySet());
        RowShape shape = mapShapes.size() < MAX_MAP_SHAPES
                ? mapShapes.computeIfAbsent(names, RowShape::of) : mapShapes.getOrDefault(names, RowShape.of(names));
        return new Reading(columns, new GridRow(kept(source), shape, values.values().toArray(), 0), Map.of());
    }

    private Reading fromProperties(Object source, Layout layout) {
        if (layout.columns().isEmpty()) {
            return scalar(source);
        }
        Map<String, String> errors = Map.of();
        Object[] cells = new Object[layout.columns().size()];
        for (int i = 0; i < cells.length; i++) {
            String name = layout.columns().get(i).name();
            try {
                cells[i] = shown(reader.read(source, name));
            } catch (CancellationException e) {
                throw e; // Ctrl+C is not an unreadable property
            } catch (RuntimeException e) {
                if (errors.isEmpty()) {
                    errors = new LinkedHashMap<>();
                }
                errors.put(name, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
        return new Reading(layout.columns(), new GridRow(kept(source), layout.shape(), cells, 0), errors);
    }

    private Reading fromBean(Object bean) {
        return fromProperties(bean, layouts.computeIfAbsent(bean.getClass(), type -> new Layout(beanColumns(type))));
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
        Map<String, ColumnSpec> byName = new TreeMap<>();
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
                || object instanceof File || object instanceof URI || object instanceof URL
                || object instanceof UUID;
    }
}
