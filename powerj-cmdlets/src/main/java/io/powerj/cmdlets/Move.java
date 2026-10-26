package io.powerj.cmdlets;

import java.util.List;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;

/**
 * {@code mv}: moves or renames files and folders; the last argument is the destination (specification FR-72).
 * Protected paths are not moved, unless {@code --allow-protected} is given.
 */
@CmdletInfo(name = "mv", category = "Files", summary = "Moves or renames files and folders",
        examples = {"mv old.txt new.txt", "mv *.log archive/", "ls --filter *.tmp | mv trash/", "mv -n src lib"})
public final class Move implements Cmdlet<Move.Params, Object, Object> {

    /** Parameters of {@code mv}. */
    public record Params(
            @Option(position = 0, description = "Sources (wildcards accepted), then the destination, or a block { … } that gives the destination of each source")
            List<Object> paths,
            @Option(shortName = 'f', description = "Overwrites existing targets")
            boolean force,
            @Option(shortName = 'v', description = "Emits each destination")
            boolean verbose,
            @Option(shortName = 'n', description = "Changes nothing: shows what would be done")
            boolean dryRun,
            @Option(description = "Also moves protected paths (list in the protected-paths file of the configuration folder)")
            boolean allowProtected,
            @Option(description = "Property of the pipeline objects that gives the source (String, Path or File)")
            String property) {
    }

    @Override
    public void begin(Params p, CmdletContext<Object> context) {
        context.state(Transfer.class, () -> new Transfer(new Transfer.Options(true, true, 1, p.force(), p.verbose(),
                p.dryRun(), p.allowProtected()), p.paths(), p.property(), context));
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Object> context) {
        ExecutionState.begun(context, Transfer.class).add(input, context);
    }

    @Override
    public void end(Params p, CmdletContext<Object> context) {
        ExecutionState.begun(context, Transfer.class).run(context);
    }
}
