package io.powerj.cmdlets;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.UsageException;

/** {@code mkdir}: creates folders and emits their {@link FileEntry} (specification FR-72). */
@CmdletInfo(name = "mkdir", category = "Files", summary = "Creates folders",
        examples = {"mkdir build", "mkdir -p src/main/java", "mkdir -n out/a out/b"})
public final class MakeDirectory implements Cmdlet<MakeDirectory.Params, Object, Object> {

    /** Parameters of {@code mkdir}. */
    public record Params(
            @Option(position = 0, description = "Folders to create, or a block { … } that gives the folder of each pipeline object")
            List<Object> paths,
            @Option(shortName = 'p', description = "Creates the missing parent folders; an existing folder is not an error")
            boolean parents,
            @Option(shortName = 'n', description = "Changes nothing: shows what would be done")
            boolean dryRun,
            @Option(description = "Property of the pipeline objects that gives the path (String, Path or File)")
            String property) {
    }

    @Override
    public void begin(Params p, CmdletContext<Object> context) {
        FileOps.Arguments arguments = FileOps.arguments(p.paths(), p.property(), context);
        context.state(PipelineFiles.class, () -> new PipelineFiles(p.property(), arguments.block(), context, FileOps.KEYS));
        if (arguments.words().isEmpty() && !context.hasInput()) {
            throw new UsageException(Messages.get("fileops.path.expected", "mkdir"));
        }
        for (String argument : arguments.words()) {
            Path path = FileOps.path(argument, context);
            if (path != null) {
                make(p, path, context);
            }
        }
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Object> context) {
        Path path = ExecutionState.begun(context, PipelineFiles.class).file(input, context);
        if (path != null) {
            make(p, path.toAbsolutePath().normalize(), context);
        }
    }

    private static void make(Params p, Path path, CmdletContext<Object> context) {
        if (p.dryRun()) {
            context.emit(new FileOperation("mkdir", path, null));
            return;
        }
        try {
            if (p.parents()) {
                Files.createDirectories(path);
            } else {
                Files.createDirectory(path);
            }
        } catch (FileAlreadyExistsException e) {
            context.error(Messages.get(FileOps.isDirectory(path) ? "mkdir.exists" : "fileops.exists.file", path));
            return;
        } catch (NoSuchFileException e) {
            context.error(Messages.get("fileops.parent.missing", path));
            return;
        } catch (IOException e) {
            FileOps.report(e, path, context);
            return;
        }
        FileEntries.of(path, LinkOption.NOFOLLOW_LINKS).ifPresent(context::emit);
    }
}
