package io.powerj.cmdlets;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;

/**
 * {@code to-csv}: the objects received as CSV lines (specification FR-73), emitted as they arrive: the header, then
 * one line per object. The columns are those of the first object.
 */
@CmdletInfo(name = "to-csv", preview = true, category = "Data", summary = "Converts the pipeline objects to CSV lines",
        examples = {"ls | select name size | to-csv", "ls | select name size modified | to-csv > files.csv",
                "ls | select name size | to-csv -d \";\" > excel.csv"})
public final class ToCsv implements Cmdlet<ToCsv.Params, Object, String> {

    /** Parameters of {@code to-csv}. */
    public record Params(
            @Option(shortName = 'd', description = "Field separator: one character, or tab (default ,)")
            String delimiter,
            @Option(description = "Writes no header line")
            boolean noHeader) {
    }

    /** One execution: the columns, known at the first object. */
    private static final class State {
        final CsvWriter writer;
        final ObjectProperties properties;
        List<String> columns;
        /** Columns of later objects that the header does not have, already reported. */
        final Set<String> reported = new HashSet<>();

        State(CsvWriter writer, ObjectProperties properties) {
            this.writer = writer;
            this.properties = properties;
        }
    }

    private static State state(Params p, CmdletContext<String> context) {
        return context.state(State.class, () -> {
            ObjectProperties properties = new ObjectProperties(context);
            return new State(new CsvWriter(CsvWriter.delimiter(p.delimiter()), properties), properties);
        });
    }

    @Override
    public void begin(Params p, CmdletContext<String> context) {
        state(p, context); // validates the delimiter before anything is read
    }

    @Override
    public void process(Params p, Object input, CmdletContext<String> context) {
        State state = state(p, context);
        boolean first = state.columns == null;
        List<String> columns = first ? ObjectProperties.columns(input) : state.columns;
        List<Object> values = new ArrayList<>(columns.size());
        try {
            for (String column : columns) {
                values.add(state.properties.get(input, column));
            }
        } catch (ObjectProperties.Unreadable e) {
            context.error(Messages.get("pipeline.object.skipped", e.getMessage(), Texts.abbreviate(input)));
            return;
        }
        if (!first) {
            reportMissingColumns(state, ObjectProperties.columns(input), context);
        }
        if (first) {
            state.columns = columns;
            if (!p.noHeader()) {
                context.emit(state.writer.line(columns));
            }
        }
        context.emit(state.writer.line(values));
    }

    /**
     * The columns come from the first object: a later one that has others loses them. Each one is named, once, when it first
     * appears (from-csv warns in the symmetric case, {@code csv.extra.fields}); a column that is missing is an empty field.
     */
    private static void reportMissingColumns(State state, List<String> own, CmdletContext<String> context) {
        List<String> absent = new ArrayList<>();
        for (String column : own) {
            if (!state.columns.contains(column) && state.reported.add(column)) {
                absent.add(column);
            }
        }
        if (!absent.isEmpty()) {
            context.error(Messages.get("to-csv.columns.dropped", String.join(", ", absent)));
        }
    }
}
