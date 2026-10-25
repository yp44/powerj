package io.powerj.cmdlets;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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
 * select content={ (cat file.txt) } other={ (cat another.txt) }
 * </pre>
 * At the head of a pipeline, {@code select} emits one {@link Row} whose blocks are evaluated once, with no current object.
 * A property missing on an object gives an empty cell, with one non-blocking error per column.
 */
@CmdletInfo(name = "select", preview = true, category = "Filters", summary = "Chooses and computes the columns of the objects",
        examples = {"ls | select name size", "ls | select file=name kb={ $_.size / 1kb }",
                "ls -r | select path.fileName size | sort size -d | first 5",
                "env | select name len={ $_.value.length() } | sort len -d | first 3",
                "select content={ (cat file.txt) } other={ (cat another.txt) }"})
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

    private static State state(Params p, CmdletContext<Row> context) {
        return context.state(State.class, () -> new State(columns(p.columns(), context)));
    }

    @Override
    public void begin(Params p, CmdletContext<Row> context) {
        State state = state(p, context);
        if (!context.hasInput()) {
            // Head of a pipeline: a single row, its blocks evaluated once with no current object.
            state.sizeColumns = Set.of();
            context.emit(row(state, null, context));
        }
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Row> context) {
        State state = state(p, context);
        if (state.sizeColumns == null) {
            state.sizeColumns = sizeColumns(state.columns, input);
        }
        context.emit(row(state, input, context));
    }

    private static Row row(State state, Object input, CmdletContext<Row> context) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Column column : state.columns) {
            var attempt = column.value().tryInvoke(input);
            if (attempt.failed() && state.reported.add(column.name())) {
                context.error(Messages.get("select.column.failed", column.name(), attempt.error().getMessage()));
            }
            values.put(column.name(), attempt.value()); // null when it failed
        }
        return Row.of(values, state.sizeColumns);
    }

    /** Column names of the arguments, by the naming rules of {@link #columns(List, CmdletContext)}, for completion. */
    @Override
    public List<String> columns(String arguments) {
        List<String> tokens = new ArrayList<>(); // words, and "{" for a block
        int i = 0;
        while (i < arguments.length()) {
            char c = arguments.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '{') {
                int depth = 0;
                while (i < arguments.length()) {
                    char d = arguments.charAt(i++);
                    if (d == '{') {
                        depth++;
                    } else if (d == '}' && --depth == 0) {
                        break;
                    }
                }
                tokens.add("{");
            } else {
                int start = i;
                while (i < arguments.length() && !Character.isWhitespace(arguments.charAt(i)) && arguments.charAt(i) != '{') {
                    i++;
                }
                tokens.add(arguments.substring(start, i));
            }
        }
        List<String> names = new ArrayList<>();
        int unnamed = 0;
        for (int k = 0; k < tokens.size(); k++) {
            String token = tokens.get(k);
            if (token.equals("{")) {
                names.add("column" + ++unnamed);
            } else if (token.startsWith("-")) {
                if (token.equals("--on-error")) {
                    k++; // its value
                }
            } else if (token.endsWith("=")) {
                names.add(token.substring(0, token.length() - 1));
                if (k + 1 < tokens.size() && tokens.get(k + 1).equals("{")) {
                    k++;
                }
            } else if (token.contains("=")) {
                names.add(token.substring(0, token.indexOf('=')));
            } else {
                names.add(token.substring(token.lastIndexOf('.') + 1));
            }
        }
        names.removeIf(String::isEmpty);
        return names;
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

    /** Columns that read a {@link io.powerj.api.Bytes} component of the first object received. */
    private static Set<String> sizeColumns(List<Column> columns, Object first) {
        Set<String> sizes = new HashSet<>();
        for (Column column : columns) {
            if (Sizes.isSizeProperty(first, column.property())) {
                sizes.add(column.name());
            }
        }
        return sizes;
    }
}
