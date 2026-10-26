package io.powerj.cmdlets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.UsageException;
import io.powerj.fs.Globs;

/**
 * {@code touch}: creates empty files, or sets the modification time of existing ones to now, and emits their
 * {@link FileEntry} (specification FR-72).
 */
@CmdletInfo(name = "touch", category = "Files", summary = "Creates empty files or updates their modification time",
        examples = {"touch notes.txt", "touch a.txt b.txt", "ls --filter *.java | touch"})
public final class Touch implements Cmdlet<Touch.Params, Object, Object> {

    /** Parameters of {@code touch}. */
    public record Params(
            @Option(position = 0, description = "Files to create or update, or a block { … } that gives the file of each pipeline object")
            List<Object> paths,
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
            throw new UsageException(Messages.get("fileops.path.expected", "touch"));
        }
        for (String argument : arguments.words()) {
            if (Globs.isPattern(argument)) {
                FileOps.expand(argument, context).forEach(path -> touch(p, path.toAbsolutePath().normalize(), context));
                continue; // a wildcard updates the existing files it matches
            }
            Path path = FileOps.path(argument, context);
            if (path != null) {
                touch(p, path, context);
            }
        }
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Object> context) {
        Path path = ExecutionState.begun(context, PipelineFiles.class).file(input, context);
        if (path != null) {
            touch(p, path.toAbsolutePath().normalize(), context);
        }
    }

    private static void touch(Params p, Path path, CmdletContext<Object> context) {
        if (p.dryRun()) {
            context.emit(new FileOperation("touch", path, null));
            return;
        }
        try {
            if (FileOps.exists(path)) {
                Files.setLastModifiedTime(path, FileTime.from(Instant.now()));
            } else {
                Files.createFile(path);
            }
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
