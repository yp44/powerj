package io.powerj.cmdlets;

import java.util.List;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;

/**
 * {@code from-json}: reads the JSON text received from the pipeline (specification FR-73). Each top-level value is
 * emitted as soon as it is complete; a top-level array is unrolled; an object gives a {@code Row}.
 */
@CmdletInfo(name = "from-json", preview = true, category = "Data", summary = "Reads JSON text into objects",
        examples = {"cat data.json | from-json", "cat data.json | from-json | where { $_.age > 30 }",
                "cat events.jsonl | from-json | group type", "cat data.json -c | from-json --property content"})
public final class FromJson implements Cmdlet<FromJson.Params, Object, Object> {

    /** Parameters of {@code from-json}. */
    public record Params(
            @Option(description = "Property of the pipeline objects that holds the JSON text")
            String property) {
    }

    private record State(PipelineText text, JsonSplitter splitter) {
    }

    private static State state(Params p, CmdletContext<Object> context) {
        return context.state(State.class, () -> {
            if (!context.hasInput()) {
                throw new IllegalArgumentException(Messages.get("from-json.input.expected"));
            }
            return new State(new PipelineText(p.property(), context, "from-json.not.text"), new JsonSplitter());
        });
    }

    @Override
    public void begin(Params p, CmdletContext<Object> context) {
        state(p, context); // refuses a command that has no input before anything is read
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Object> context) {
        State state = state(p, context);
        String text = state.text().text(input, context);
        if (text != null) {
            emit(state.splitter().feed(text), context);
        }
    }

    @Override
    public void end(Params p, CmdletContext<Object> context) {
        emit(state(p, context).splitter().finish(), context);
    }

    private static void emit(List<JsonSplitter.Result> results, CmdletContext<Object> context) {
        for (JsonSplitter.Result result : results) {
            switch (result) {
                case JsonSplitter.Parsed(List<?> array) -> array.forEach(context::emit);
                case JsonSplitter.Parsed(Object value) -> context.emit(value);
                case JsonSplitter.Failed(var error) -> context.error(Messages.get("from-json.invalid", error.line(),
                        error.column(), error.getMessage()));
            }
        }
    }
}
