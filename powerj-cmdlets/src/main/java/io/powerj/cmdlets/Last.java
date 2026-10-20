package io.powerj.cmdlets;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

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

    /** Last objects received, per execution. */
    private final Map<CmdletContext<?>, State> states = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void begin(Params p, CmdletContext<Object> context) {
        int count = First.count(p.count());
        states.put(context, new State(count, new ArrayDeque<>(Math.min(count, 1024))));
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Object> context) {
        State state = states.get(context);
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
        State state = states.remove(context);
        for (Object value : state.objects()) {
            context.emit(value == NULL ? null : value);
        }
    }

    private static final Object NULL = new Object();
}
