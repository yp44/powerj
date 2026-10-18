package io.powerj.cmdlets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

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
        List<String> columns;

        State(CsvWriter writer) {
            this.writer = writer;
        }
    }

    private final Map<CmdletContext<?>, State> states = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void begin(Params p, CmdletContext<String> context) {
        states.put(context, new State(new CsvWriter(CsvWriter.delimiter(p.delimiter()))));
    }

    @Override
    public void process(Params p, Object input, CmdletContext<String> context) {
        State state = states.get(context);
        boolean first = state.columns == null;
        List<String> columns = first ? Properties.columns(input) : state.columns;
        List<Object> values = new ArrayList<>(columns.size());
        try {
            for (String column : columns) {
                values.add(Properties.get(input, column));
            }
        } catch (IllegalStateException e) {
            context.error(Messages.get("pipeline.object.skipped", e.getMessage(), Where.abbreviate(input)));
            return;
        }
        if (first) {
            state.columns = columns;
            if (!p.noHeader()) {
                context.emit(state.writer.line(columns));
            }
        }
        context.emit(state.writer.line(values));
    }

    @Override
    public void end(Params p, CmdletContext<String> context) {
        states.remove(context);
    }
}
