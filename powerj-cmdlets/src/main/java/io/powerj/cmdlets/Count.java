package io.powerj.cmdlets;

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

    /** Objects received so far. */
    private static final class Tally {
        long objects;
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Long> context) {
        context.state(Tally.class, Tally::new).objects++;
    }

    @Override
    public void end(Params p, CmdletContext<Long> context) {
        context.emit(context.state(Tally.class, Tally::new).objects);
    }
}
