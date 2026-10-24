package io.powerj.cmdlets;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.stream.BaseStream;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.Row;
import io.powerj.api.ScriptBlock;

/**
 * {@code join}: one {@link Row} per pair of a pipeline object and an object of {@code --right} with the same key
 * (specification FR-78).
 * <pre>
 * cat countries.csv | from-csv | join -r { (cat zip.csv | from-csv) } --left-key statezip --right-key zipcode
 * join -l { (cat a.csv | from-csv) } -r { (cat b.csv | from-csv) } -k code
 * ls | join -r { (cat sizes.csv | from-csv) } -k name -o
 * </pre>
 * The right side is read once and indexed by key; the left side is streamed. A row has the columns of the left object,
 * then those of the right object; a right column whose name is already taken is renamed {@code right_<name>}.
 */
@CmdletInfo(name = "join", preview = true, category = "Filters", summary = "Joins the objects with those of another source by key",
        examples = {"cat a.csv | from-csv | join -r { (cat b.csv | from-csv) } -k id",
                "cat countries.csv | from-csv | join -r { (cat zip.csv | from-csv) } --left-key statezip --right-key { $_.zipcode.toUpperCase() }",
                "join -l { (cat a.csv | from-csv) } -r { (cat b.csv | from-csv) } -k code",
                "ls | join -r { (cat sizes.csv | from-csv) } -k name -o"})
public final class Join implements Cmdlet<Join.Params, Object, Row> {

    /** Parameters of {@code join}. */
    public record Params(
            @Option(shortName = 'r', description = "Right side: block { … } evaluated once, its objects joined to the left ones")
            Object right,
            @Option(shortName = 'l', description = "Left side, at the head of a pipeline: block { … } evaluated once")
            Object left,
            @Option(shortName = 'k', description = "Key of both sides: property, property path or block { … }")
            Object key,
            @Option(description = "Key of the left objects: property, property path or block { … }")
            Object leftKey,
            @Option(description = "Key of the right objects (default: the left key): property, property path or block { … }")
            Object rightKey,
            @Option(shortName = 'o', description = "Keeps the left objects without a match, with empty right columns")
            boolean outer) {
    }

    /** Prefix of a right column whose name is already a left column. */
    static final String RIGHT_PREFIX = "right_";

    /**
     * Right side indexed by key.
     *
     * @param dropped   right column left out of the rows (the common key of {@code --key}), {@code null} if none
     * @param columns   columns of the first right object, for the left objects without a match
     */
    private record State(ScriptBlock leftKey, Map<Object, List<Object>> index, String dropped, List<String> columns) {
    }

    private final Map<CmdletContext<?>, State> states = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void begin(Params p, CmdletContext<Row> context) {
        ScriptBlock right = side(p.right(), "join.right.expected");
        if (p.key() != null && (p.leftKey() != null || p.rightKey() != null)) {
            throw new IllegalArgumentException(Messages.get("join.key.conflict"));
        }
        Object leftKeyArgument = p.key() != null ? p.key() : p.leftKey();
        if (leftKeyArgument == null) {
            throw new IllegalArgumentException(Messages.get("join.key.expected"));
        }
        Object rightKeyArgument = p.rightKey() != null ? p.rightKey() : leftKeyArgument;
        ScriptBlock leftKey = Keys.block(leftKeyArgument, context, "join.key.invalid");
        ScriptBlock rightKey = Keys.block(rightKeyArgument, context, "join.key.invalid");
        ScriptBlock left = null;
        if (context.hasInput() && p.left() != null) {
            throw new IllegalArgumentException(Messages.get("join.left.conflict"));
        }
        if (!context.hasInput()) {
            left = side(p.left(), "join.left.expected");
        }

        Map<Object, List<Object>> index = new HashMap<>();
        List<String> columns = null;
        for (Object object : objects(right, "--right")) {
            if (columns == null) {
                columns = Properties.columns(object);
            }
            Object key;
            try {
                key = rightKey.invoke(object);
            } catch (CancellationException e) {
                throw e;
            } catch (RuntimeException e) {
                context.error(Messages.get("pipeline.object.skipped", e.getMessage(), Where.abbreviate(object)));
                continue;
            }
            if (key != null) {
                index.computeIfAbsent(Keys.normalized(key), k -> new ArrayList<>()).add(object);
            }
        }
        String dropped = p.key() instanceof String property && !property.contains(".") ? property : null;
        State state = new State(leftKey, index, dropped, columns == null ? List.of() : columns);
        states.put(context, state);
        if (left != null) {
            for (Object object : objects(left, "--left")) {
                join(state, p, object, context);
            }
        }
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Row> context) {
        join(states.get(context), p, input, context);
    }

    @Override
    public void end(Params p, CmdletContext<Row> context) {
        states.remove(context);
    }

    private static void join(State state, Params p, Object left, CmdletContext<Row> context) {
        Object key;
        try {
            key = state.leftKey().invoke(left);
        } catch (CancellationException e) {
            throw e;
        } catch (RuntimeException e) {
            context.error(Messages.get("pipeline.object.skipped", e.getMessage(), Where.abbreviate(left)));
            return;
        }
        List<Object> matches = key == null ? List.of() : state.index().getOrDefault(Keys.normalized(key), List.of());
        if (matches.isEmpty()) {
            if (p.outer()) {
                context.emit(row(state, left, null));
            }
            return;
        }
        for (Object right : matches) {
            context.emit(row(state, left, right));
        }
    }

    /** Row of the columns of {@code left}, then of {@code right} ({@code null}: empty right columns). */
    private static Row row(State state, Object left, Object right) {
        Map<String, Object> values = new LinkedHashMap<>();
        Set<String> sizes = new HashSet<>();
        for (String column : Properties.columns(left)) {
            values.put(column, Properties.get(left, column));
            if (isSize(left, column)) {
                sizes.add(column);
            }
        }
        for (String column : right == null ? state.columns() : Properties.columns(right)) {
            if (column.equals(state.dropped())) {
                continue;
            }
            String name = values.containsKey(column) ? RIGHT_PREFIX + column : column;
            values.put(name, right == null ? null : Properties.get(right, column));
            if (right != null && isSize(right, column)) {
                sizes.add(name);
            }
        }
        return Row.of(values, sizes);
    }

    private static boolean isSize(Object value, String column) {
        return value instanceof Row row ? row.sizeColumns().contains(column) : Sizes.isSizeProperty(value, column);
    }

    private static ScriptBlock side(Object argument, String expectedKey) {
        if (argument instanceof ScriptBlock block) {
            return block;
        }
        throw new IllegalArgumentException(Messages.get(expectedKey));
    }

    /** Objects given by a side, a collection, an array or a stream being unrolled as by the pipeline (FR-30b). */
    private static List<Object> objects(ScriptBlock side, String option) {
        Object value;
        try {
            value = side.invoke(null);
        } catch (CancellationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(Messages.get("join.side.failed", option, e.getMessage()), e);
        }
        List<Object> objects = new ArrayList<>();
        switch (value) {
            case null -> {
            }
            case java.nio.file.Path path -> objects.add(path);
            case Collection<?> items -> objects.addAll(items);
            case Iterable<?> items -> items.forEach(objects::add);
            case BaseStream<?, ?> stream -> {
                try (stream) {
                    stream.iterator().forEachRemaining(objects::add);
                }
            }
            case Iterator<?> iterator -> iterator.forEachRemaining(objects::add);
            case Optional<?> optional -> optional.ifPresent(objects::add);
            case Object array when array.getClass().isArray() -> {
                for (int i = 0; i < java.lang.reflect.Array.getLength(array); i++) {
                    objects.add(java.lang.reflect.Array.get(array, i));
                }
            }
            default -> objects.add(value);
        }
        return objects;
    }
}
