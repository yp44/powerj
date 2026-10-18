package io.powerj.cmdlets;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;

/** {@code cp}: copies files and folders; the last argument is the destination (specification FR-72). */
@CmdletInfo(name = "cp", category = "Files", summary = "Copies files and folders",
        examples = {"cp a.txt b.txt", "cp *.txt backup/", "cp -r src src-copy", "ls --filter *.log | cp archive/",
                "cp -n -r docs docs-old", "cp -r -p 16 photos /mnt/backup/"})
public final class Copy implements Cmdlet<Copy.Params, Object, Object> {

    /** Parameters of {@code cp}. */
    public record Params(
            @Option(position = 0, description = "Sources (wildcards accepted), then the destination, or a block { … } that gives the destination of each source")
            List<Object> paths,
            @Option(shortName = 'r', description = "Copies folders with their content")
            boolean recurse,
            @Option(shortName = 'p', description = "Copies up to n entries of a folder at the same time, in virtual "
                    + "threads")
            Integer parallel,
            @Option(shortName = 'f', description = "Overwrites existing targets")
            boolean force,
            @Option(shortName = 'v', description = "Emits each destination created")
            boolean verbose,
            @Option(shortName = 'n', description = "Changes nothing: shows what would be done")
            boolean dryRun,
            @Option(description = "Property of the pipeline objects that gives the source (String, Path or File)")
            String property) {
    }

    private final Map<CmdletContext<?>, Transfer> transfers = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void begin(Params p, CmdletContext<Object> context) {
        transfers.put(context, new Transfer(new Transfer.Options(false, p.recurse(),
                FileOps.parallelism(p.parallel()), p.force(), p.verbose(), p.dryRun(), false), p.paths(), p.property(), context));
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Object> context) {
        transfers.get(context).add(input, context);
    }

    @Override
    public void end(Params p, CmdletContext<Object> context) {
        transfers.remove(context).run(context);
    }
}
