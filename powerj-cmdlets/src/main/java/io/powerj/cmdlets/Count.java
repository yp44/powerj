package io.powerj.cmdlets;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;

/** {@code count}: the number of objects received, emitted when the input ends (specification FR-70). */
@CmdletInfo(name = "count", preview = true, category = "Filters", summary = "Counts the pipeline objects",
        examples = {"ls -r | count", "ls -r | where { $_.ext == \"java\" } | count", "git log --oneline | count"})
public final class Count implements Cmdlet<Count.Params, Object, Long> {

    /** {@code count} has no options. */
    public record Params() {
    }

    /** Objects received, per execution. */
    private final Map<CmdletContext<?>, long[]> counts = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void begin(Params p, CmdletContext<Long> context) {
        counts.put(context, new long[1]);
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Long> context) {
        counts.get(context)[0]++;
    }

    @Override
    public void end(Params p, CmdletContext<Long> context) {
        context.emit(counts.remove(context)[0]);
    }
}
