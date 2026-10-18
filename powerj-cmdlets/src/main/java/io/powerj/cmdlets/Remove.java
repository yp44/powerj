package io.powerj.cmdlets;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;

/**
 * {@code rm}: removes files and folders (specification FR-72). A non-empty folder needs {@code -r}; links are removed,
 * never followed; protected paths, a folder that contains one, and the current folder with its parents are refused
 * unless {@code --allow-protected} is given.
 */
@CmdletInfo(name = "rm", category = "Files", summary = "Removes files and folders",
        examples = {"rm notes.txt", "rm *.tmp", "rm -r build", "ls -r --filter *.log | rm -v", "rm -r -n target"})
public final class Remove implements Cmdlet<Remove.Params, Object, Object> {

    /** Parameters of {@code rm}. */
    public record Params(
            @Option(position = 0, description = "Files and folders to remove (wildcards accepted), or a block { … } that gives the path of each pipeline object")
            List<Object> paths,
            @Option(shortName = 'r', description = "Removes folders with their content")
            boolean recurse,
            @Option(shortName = 'f', description = "A missing path is not an error")
            boolean force,
            @Option(shortName = 'v', description = "Emits each entry removed")
            boolean verbose,
            @Option(shortName = 'n', description = "Changes nothing: shows what would be removed")
            boolean dryRun,
            @Option(description = "Also removes protected paths (list in the protected-paths file of the configuration folder)")
            boolean allowProtected,
            @Option(description = "Property of the pipeline objects that gives the path (String, Path or File)")
            String property) {
    }

    private record State(PipelineFiles files, ProtectedPaths protectedPaths) {
    }

    private final Map<CmdletContext<?>, State> states = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void begin(Params p, CmdletContext<Object> context) {
        FileOps.Arguments arguments = FileOps.arguments(p.paths(), p.property(), context);
        var state = new State(new PipelineFiles(p.property(), arguments.block(), context, FileOps.KEYS),
                p.allowProtected() ? null : ProtectedPaths.load(context.configDirectory(), context.environment(),
                        context.currentDirectory()));
        states.put(context, state);
        if (arguments.words().isEmpty() && !context.hasInput()) {
            throw new IllegalArgumentException(Messages.get("fileops.path.expected", "rm"));
        }
        for (String argument : arguments.words()) {
            for (Path path : FileOps.expand(argument, context)) {
                remove(p, state, path.toAbsolutePath().normalize(), context);
            }
        }
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Object> context) {
        State state = states.get(context);
        Path path = state.files().file(input, context);
        if (path != null) {
            remove(p, state, path.toAbsolutePath().normalize(), context);
        }
    }

    @Override
    public void end(Params p, CmdletContext<Object> context) {
        states.remove(context);
    }

    private static void remove(Params p, State state, Path path, CmdletContext<Object> context) {
        if (!FileOps.exists(path)) {
            if (!p.force()) {
                context.error(Messages.get("fileops.not.found", path));
            }
            return;
        }
        if (state.protectedPaths() != null) {
            var refusal = state.protectedPaths().refusal(path);
            if (refusal.isPresent()) {
                context.error(refusal.get());
                return;
            }
        }
        if (FileOps.isDirectory(path) && !p.recurse()) {
            try {
                if (!FileOps.isEmptyDirectory(path)) {
                    context.error(Messages.get("fileops.not.empty", path));
                    return;
                }
            } catch (IOException e) {
                FileOps.report(e, path, context);
                return;
            }
        }
        FileOps.deleteTree(path, p.dryRun(), p.verbose(), context, (removed, entry) -> {
            if (p.dryRun()) {
                context.emit(new FileOperation("remove", removed, null));
            } else if (entry != null) {
                context.emit(entry);
            }
        });
    }
}
