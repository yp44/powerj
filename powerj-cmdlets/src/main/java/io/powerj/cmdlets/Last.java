package io.powerj.cmdlets;

import java.util.ArrayDeque;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;

/**
 * {@code last}: the last {@code n} objects of the pipeline, in their order, emitted when the input ends
 * (specification FR-68). At most {@code n} objects are kept in memory.
 */
@CmdletInfo(name = "last", preview = true, passThrough = true, category = "Filters", summary = "Keeps the last objects of the pipeline",
        examples = {"ls | last", "ls | sort size | last 3", "cat app.log | last 20"})
public final class Last implements Cmdlet<Last.Params, Object, Object> {

    /** Parameters of {@code last}. */
    public record Params(
            @Option(position = 0, description = "Number of objects kept (default: 1)")
            Integer count) {
    }

    private record State(int count, ArrayDeque<Object> objects) {
    }

    /** Last objects received. */
    private static State state(Params p, CmdletContext<Object> context) {
        return context.state(State.class, () -> {
            int count = First.count(p.count());
            return new State(count, new ArrayDeque<>(Math.min(count, 1024)));
        });
    }

    @Override
    public void begin(Params p, CmdletContext<Object> context) {
        state(p, context); // validates the count before anything is read
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Object> context) {
        State state = state(p, context);
        if (state.count() == 0) {
            return;
        }
        if (state.objects().size() == state.count()) {
            state.objects().removeFirst();
        }
        // ArrayDeque refuses null: it is wrapped, and unwrapped when emitted.
        state.objects().addLast(input == null ? NULL : input);
    }

    @Override
    public void end(Params p, CmdletContext<Object> context) {
        for (Object value : state(p, context).objects()) {
            context.emit(value == NULL ? null : value);
        }
    }

    private static final Object NULL = new Object();
}
