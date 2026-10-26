package io.powerj.cmdlets;

import java.util.ArrayList;
import java.util.List;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Collected;

/**
 * {@code collect}: gathers all received objects into a single list (specification FR-36d), like
 * {@code Stream.toList()}. The list is always a list, even when empty or with a single element, and the next
 * stage receives it whole ({@link Collected} is not unrolled).
 * <pre>
 * (ls -r | where { f -> !f.dir } | collect).size()
 * ls -r | collect | map { l -> l.stream().sorted((a, b) -> Long.compare(b.size, a.size)).limit(5).toList() }
 * </pre>
 */
@CmdletInfo(name = "collect", preview = true, category = "Filters", summary = "Gathers the pipeline objects into a single list",
        examples = {"(ls -r | collect).size()", "$l = ls | where { f -> f.dir } | collect",
                "ls -r | collect | map { l -> l.stream().sorted((a, b) -> Long.compare(b.size, a.size)).limit(5).toList() }"})
public final class Collect implements Cmdlet<Collect.Params, Object, Collected> {

    /** {@code collect} has no options. */
    public record Params() {
    }

    /** Received objects. */
    private static final class Received {
        final List<Object> objects = new ArrayList<>();
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Collected> context) {
        context.state(Received.class, Received::new).objects.add(input);
    }

    @Override
    public void end(Params p, CmdletContext<Collected> context) {
        context.emit(new Collected<>(context.state(Received.class, Received::new).objects));
    }
}
