package io.powerj.cmdlets;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

import io.powerj.api.Bytes;
import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.Row;
import io.powerj.api.ScriptBlock;

/**
 * {@code select}: turns each object into a {@link Row} of chosen columns (specification FR-69).
 * <pre>
 * ls | select name size
 * ls | select file=name kb={ $_.size / 1kb }
 * ls -r | select path.fileName size
 * </pre>
 * A property missing on an object gives an empty cell, with one non-blocking error per column.
 */
@CmdletInfo(name = "select", preview = true, category = "Filters", summary = "Chooses and computes the columns of the objects",
        examples = {"ls | select name size", "ls | select file=name kb={ $_.size / 1kb }",
                "ls -r | select path.fileName size | sort size -d | first 5",
                "env | select name len={ $_.value.length() } | sort len -d | first 3"})
public final class Select implements Cmdlet<Select.Params, Object, Row> {

    /** Parameters of {@code select}. */
    public record Params(
            @Option(position = 0, description = "Columns: property, property path, name=property, or name={ … } for a computed column")
            List<Object> columns) {
    }

    /**
     * Column of the rows.
     *
     * @param property name of the record component read directly, to recognize a size column; {@code null} otherwise
     */
    record Column(String name, ScriptBlock value, String property) {
    }

    private static final Pattern NAME = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*");

    private static final class State {
        final List<Column> columns;
        final Set<String> reported = new HashSet<>();
        Set<String> sizeColumns;

        State(List<Column> columns) {
            this.columns = columns;
        }
    }

    private final Map<CmdletContext<?>, State> states = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void begin(Params p, CmdletContext<Row> context) {
        states.put(context, new State(columns(p.columns(), context)));
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Row> context) {
        State state = states.get(context);
        if (state.sizeColumns == null) {
            state.sizeColumns = sizeColumns(state.columns, input);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (Column column : state.columns) {
            Object value;
            try {
                value = column.value().invoke(input);
            } catch (CancellationException e) {
                throw e;
            } catch (RuntimeException e) {
                value = null;
                if (state.reported.add(column.name())) {
                    context.error(Messages.get("select.column.failed", column.name(), e.getMessage()));
                }
            }
            values.put(column.name(), value);
        }
        context.emit(Row.of(values, state.sizeColumns));
    }

    @Override
    public void end(Params p, CmdletContext<Row> context) {
        states.remove(context);
    }

    /** Columns described by the arguments, in order. */
    static List<Column> columns(List<Object> arguments, CmdletContext<?> context) {
        if (arguments == null || arguments.isEmpty()) {
            throw new IllegalArgumentException(Messages.get("select.column.expected"));
        }
        List<Column> columns = new ArrayList<>();
        int unnamed = 0;
        for (int i = 0; i < arguments.size(); i++) {
            Object argument = arguments.get(i);
            Column column;
            if (argument instanceof ScriptBlock block) {
                column = new Column("column" + ++unnamed, block, null);
            } else if (argument instanceof String word && word.endsWith("=") && word.indexOf('=') == word.length() - 1) {
                String name = name(word.substring(0, word.length() - 1));
                if (i + 1 >= arguments.size() || !(arguments.get(i + 1) instanceof ScriptBlock block)) {
                    throw new IllegalArgumentException(Messages.get("select.block.expected", word));
                }
                i++;
                column = new Column(name, block, null);
            } else if (argument instanceof String word && word.contains("=")) {
                int equals = word.indexOf('=');
                String name = name(word.substring(0, equals));
                String property = word.substring(equals + 1);
                column = new Column(name, Keys.block(property, context, "select.column.invalid"), simple(property));
            } else if (argument instanceof String property) {
                String name = property.substring(property.lastIndexOf('.') + 1);
                column = new Column(name, Keys.block(property, context, "select.column.invalid"), simple(property));
            } else {
                throw new IllegalArgumentException(Messages.get("select.column.invalid", argument));
            }
            for (Column other : columns) {
                if (other.name().equals(column.name())) {
                    throw new IllegalArgumentException(Messages.get("select.column.duplicate", column.name()));
                }
            }
            columns.add(column);
        }
        return columns;
    }

    private static String name(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(Messages.get("select.name.invalid", name));
        }
        return name;
    }

    /** The property itself if it is a single name (a possible size component), otherwise {@code null}. */
    private static String simple(String property) {
        return property.contains(".") ? null : property;
    }

    /** Columns that read a {@link Bytes} component of the first object received. */
    private static Set<String> sizeColumns(List<Column> columns, Object first) {
        if (first == null || !first.getClass().isRecord()) {
            return Set.of();
        }
        Set<String> sizes = new HashSet<>();
        for (Column column : columns) {
            if (column.property() == null) {
                continue;
            }
            for (RecordComponent component : first.getClass().getRecordComponents()) {
                if (component.getName().equals(column.property()) && component.isAnnotationPresent(Bytes.class)) {
                    sizes.add(column.name());
                }
            }
        }
        return sizes;
    }
}
