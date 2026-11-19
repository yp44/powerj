package io.powerj.cmdlets;

import java.io.IOException;
import java.nio.file.Files;
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

/**
 * {@code touch}: creates empty files, or sets the modification time of existing ones to now, and emits their
 * {@link FileEntry} (specification FR-72).
 */
@CmdletInfo(name = "touch", category = "Files", summary = "Creates empty files or updates their modification time",
        examples = {"touch notes.txt", "touch a.txt b.txt", "ls --filter *.java | touch"})
public final class Touch implements Cmdlet<Touch.Params, Object, Object> {

    /** Parameters of {@code touch}. */
    public record Params(
            @Option(position = 0, path = true, description = "Files to create or update, or a block { … } that gives the file of each pipeline object")
            List<Object> paths,
            @Option(shortName = 'n', description = "Changes nothing: makes the checks of the real run and says for each file whether it would be created or updated")
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
        for (Object argument : arguments.words()) {
            if (FileArguments.isWildcard(argument)) {
                FileOps.expand(argument, context).forEach(path -> touch(p, path.toAbsolutePath().normalize(), context));
                continue; // a wildcard updates the existing files it matches
            }
            Path path = FileOps.path(FileArguments.text(argument), context);
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

    /**
     * Creates the file, or sets the modification time of an existing one. A symbolic link is followed, as POSIX
     * {@code touch} does: its target is updated and the link stays; a broken link is reported as such (audit v2, R-18).
     * A dry run makes the same checks and says {@code create} or {@code update}.
     */
    private static void touch(Params p, Path path, CmdletContext<Object> context) {
        boolean exists = FileOps.exists(path);
        if (exists && Files.isSymbolicLink(path) && !Files.exists(path)) {
            context.error(Messages.get("touch.link.broken", path));
            return;
        }
        if (p.dryRun()) {
            Path parent = path.getParent();
            if (!exists && parent != null && !FileOps.isFolderOrLinkToFolder(parent)) {
                context.error(Messages.get("fileops.parent.missing", path));
                return;
            }
            context.emit(new FileOperation(exists ? "update" : "create", path, null));
            return;
        }
        try {
            if (exists) {
                Files.setLastModifiedTime(path, FileTime.from(Instant.now()));
            } else {
                Files.createFile(path);
            }
        } catch (NoSuchFileException e) {
            // the parent is missing; any other reason is the file's own
            Path parent = path.getParent();
            context.error(parent != null && !Files.exists(parent) ? Messages.get("fileops.parent.missing", path)
                    : Messages.get("fileops.not.found", path));
            return;
        } catch (IOException e) {
            FileOps.report(e, path, context);
            return;
        }
        FileEntries.of(path).ifPresent(context::emit);
    }
}
