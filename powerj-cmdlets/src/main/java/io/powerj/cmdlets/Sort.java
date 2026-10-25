package io.powerj.cmdlets;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.ScriptBlock;

/**
 * {@code sort}: sorts the pipeline objects (specification FR-67). The sort is stable; {@code null} comes first in
 * ascending order. The objects are emitted once the input has ended.
 * <pre>
 * ls | sort size --desc
 * ls | sort dir name
 * ls -r | sort { $_.name.length() }
 * </pre>
 */
@CmdletInfo(name = "sort", preview = true, passThrough = true, category = "Filters", summary = "Sorts pipeline objects by keys",
        examples = {"ls | sort size --desc", "ls | sort dir name", "ls -r | sort { $_.name.length() } -d",
                "(ls)*.name | sort -i", "git branch | sort"})
public final class Sort implements Cmdlet<Sort.Params, Object, Object> {

    /** Parameters of {@code sort}. */
    public record Params(
            @Option(position = 0, description = "Sort keys: property, property path or block { … }; none: the objects themselves")
            List<Object> keys,
            @Option(shortName = 'd', description = "Descending order")
            boolean desc,
            @Option(shortName = 'i', description = "Compares strings ignoring case")
            boolean ignoreCase) {
    }

    /** Object received, with its key values computed once. */
    private record Entry(Object[] keys, Object value) {
    }

    private record State(List<ScriptBlock> keys, List<Entry> entries, SortComparator comparator) {
    }

    private static State state(Params p, CmdletContext<Object> context) {
        return context.state(State.class, () -> new State(Keys.blocks(p.keys(), context, "sort.key.invalid"),
                new ArrayList<>(), new SortComparator(p.ignoreCase(), context::error)));
    }

    @Override
    public void begin(Params p, CmdletContext<Object> context) {
        state(p, context); // compiles the keys before anything is read
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Object> context) {
        State state = state(p, context);
        Object[] keys;
        if (state.keys().isEmpty()) {
            keys = new Object[] {input};
        } else {
            keys = new Object[state.keys().size()];
            try {
                for (int i = 0; i < keys.length; i++) {
                    keys[i] = state.keys().get(i).invoke(input);
                }
            } catch (CancellationException e) {
                throw e;
            } catch (RuntimeException e) {
                context.error(Messages.get("pipeline.object.skipped", e.getMessage(), Where.abbreviate(input)));
                return;
            }
        }
        state.entries().add(new Entry(keys, input));
    }

    @Override
    public void end(Params p, CmdletContext<Object> context) {
        State state = state(p, context);
        Comparator<Entry> order = (a, b) -> {
            for (int i = 0; i < a.keys().length; i++) {
                int result = state.comparator().compare(a.keys()[i], b.keys()[i]);
                if (result != 0) {
                    return result;
                }
            }
            return 0;
        };
        state.entries().sort(p.desc() ? order.reversed() : order);
        for (Entry entry : state.entries()) {
            context.emit(entry.value());
        }
    }
}
