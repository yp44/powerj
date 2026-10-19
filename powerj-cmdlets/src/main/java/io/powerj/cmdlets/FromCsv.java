package io.powerj.cmdlets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.Row;

/**
 * {@code from-csv}: reads the CSV text received from the pipeline (specification FR-73), one {@code Row} per record,
 * emitted as soon as the record is complete. The first record names the columns; every value is a {@code String}.
 */
@CmdletInfo(name = "from-csv", preview = true, category = "Data", summary = "Reads CSV text into rows",
        examples = {"cat data.csv | from-csv", "cat excel.csv | from-csv -d \";\"",
                "cat data.csv | from-csv | where { $_.city == \"Paris\" }", "cat data.csv | from-csv --no-header"})
public final class FromCsv implements Cmdlet<FromCsv.Params, Object, Row> {

    /** Parameters of {@code from-csv}. */
    public record Params(
            @Option(shortName = 'd', description = "Field separator: one character, or tab (default ,)")
            String delimiter,
            @Option(description = "The first line is data: the columns are named c1, c2…")
            boolean noHeader,
            @Option(description = "Property of the pipeline objects that holds the CSV text")
            String property) {
    }

    /** One execution: the reader, and the columns once known. */
    private static final class State {
        final PipelineText text;
        final CsvReader reader;
        List<String> columns;

        State(PipelineText text, CsvReader reader) {
            this.text = text;
            this.reader = reader;
        }
    }

    private final Map<CmdletContext<?>, State> states = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void begin(Params p, CmdletContext<Row> context) {
        char delimiter = CsvWriter.delimiter(p.delimiter());
        if (!context.hasInput()) {
            throw new IllegalArgumentException(Messages.get("from-csv.input.expected"));
        }
        states.put(context, new State(new PipelineText(p.property(), context, "from-csv.not.text"),
                new CsvReader(delimiter)));
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Row> context) {
        State state = states.get(context);
        String text = state.text.text(input, context);
        if (text == null) {
            return;
        }
        for (CsvReader.Record record : state.reader.feed(text)) {
            if (state.columns == null) {
                if (!p.noHeader()) {
                    state.columns = names(record.fields());
                    continue;
                }
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= record.fields().size(); i++) {
                    columns.add("c" + i);
                }
                state.columns = columns;
            }
            context.emit(row(state.columns, record, context));
        }
    }

    @Override
    public void end(Params p, CmdletContext<Row> context) {
        State state = states.remove(context);
        if (state.reader.unclosed()) {
            context.error(Messages.get("csv.quote.unclosed", state.reader.recordLine()));
        }
    }

    /** Column names of the header: an empty name becomes {@code c<n>}, a repeated one gets {@code _2}, {@code _3}… */
    static List<String> names(List<String> header) {
        List<String> names = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i).isEmpty() ? "c" + (i + 1) : header.get(i);
            String unique = name;
            for (int n = 2; !used.add(unique); n++) {
                unique = name + "_" + n;
            }
            names.add(unique);
        }
        return names;
    }

    private static Row row(List<String> columns, CsvReader.Record record, CmdletContext<Row> context) {
        List<String> fields = record.fields();
        if (fields.size() > columns.size()) {
            context.error(Messages.get("csv.extra.fields", record.line(), fields.size(), columns.size()));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            values.put(columns.get(i), i < fields.size() ? fields.get(i) : null);
        }
        return Row.of(values);
    }
}
