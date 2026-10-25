package io.powerj.cmdlets;


import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.UsageException;

/**
 * {@code to-json}: the objects received as JSON text (specification FR-73), always an array, emitted when the input
 * ends; with {@code --lines}, one compact object per line as they arrive (JSON Lines).
 */
@CmdletInfo(name = "to-json", preview = true, category = "Data", summary = "Converts the pipeline objects to JSON text",
        examples = {"ls | select name size | to-json", "ls | select name size | to-json > files.json",
                "ls | to-json --compact", "ls -r | to-json -l > files.jsonl"})
public final class ToJson implements Cmdlet<ToJson.Params, Object, String> {

    /** Default of {@code --depth}. */
    static final int DEFAULT_DEPTH = 10;

    /** Parameters of {@code to-json}. */
    public record Params(
            @Option(shortName = 'c', description = "Writes the array on one line, without indentation")
            boolean compact,
            @Option(shortName = 'l', description = "JSON Lines: one compact object per line, emitted as they arrive")
            boolean lines,
            @Option(description = "Maximum nesting depth (default 10); deeper values are written as their text")
            Integer depth) {
    }

    /** One execution: the array being written. */
    private static final class State {
        final JsonWriter writer;
        final StringBuilder out = new StringBuilder();
        int count;
        boolean truncationReported;

        State(JsonWriter writer) {
            this.writer = writer;
        }
    }

    private static State state(Params p, CmdletContext<String> context) {
        return context.state(State.class, () -> {
            if (p.depth() != null && p.depth() < 1) {
                throw new UsageException(Messages.get("to-json.depth.invalid", p.depth()));
            }
            int depth = p.depth() == null ? DEFAULT_DEPTH : p.depth();
            return new State(new JsonWriter(!p.compact() && !p.lines(), depth));
        });
    }

    @Override
    public void begin(Params p, CmdletContext<String> context) {
        state(p, context); // validates the depth before anything is read
    }

    @Override
    public void process(Params p, Object input, CmdletContext<String> context) {
        State state = state(p, context);
        StringBuilder value = new StringBuilder();
        try {
            state.writer.write(input, value, p.lines() ? 0 : 1);
        } catch (Properties.Unreadable e) {
            context.error(Messages.get("pipeline.object.skipped", e.getMessage(), Where.abbreviate(input)));
            return;
        }
        if (state.writer.truncated() && !state.truncationReported) {
            state.truncationReported = true;
            context.error(Messages.get("to-json.depth.reached", p.depth() == null ? DEFAULT_DEPTH : p.depth()));
        }
        if (p.lines()) {
            context.emit(value.toString());
            return;
        }
        state.out.append(state.count++ == 0 ? "[" : ",");
        state.writer.newLine(state.out, 1);
        state.out.append(value);
    }

    @Override
    public void end(Params p, CmdletContext<String> context) {
        State state = state(p, context);
        if (p.lines()) {
            return;
        }
        if (state.count == 0) {
            context.emit("[]");
            return;
        }
        state.writer.newLine(state.out, 0);
        context.emit(state.out.append(']').toString());
    }
}
