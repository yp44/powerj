package io.powerj.cmdlets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
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
            @Option(position = 0, description = "Files to create or update")
            List<String> paths,
            @Option(shortName = 'n', description = "Changes nothing: shows what would be done")
            boolean dryRun,
            @Option(description = "Property of the pipeline objects that gives the path (String, Path or File)")
            String property) {
    }

    private final Map<CmdletContext<?>, PipelineFiles> files = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public void begin(Params p, CmdletContext<Object> context) {
        files.put(context, new PipelineFiles(p.property(), context, FileOps.KEYS));
        if (p.paths().isEmpty() && !context.hasInput()) {
            throw new IllegalArgumentException(Messages.get("fileops.path.expected", "touch"));
        }
        for (String argument : p.paths()) {
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
        Path path = files.get(context).file(input, context);
        if (path != null) {
            touch(p, path.toAbsolutePath().normalize(), context);
        }
    }

    @Override
    public void end(Params p, CmdletContext<Object> context) {
        files.remove(context);
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
