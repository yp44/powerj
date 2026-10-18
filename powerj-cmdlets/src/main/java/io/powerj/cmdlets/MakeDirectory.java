package io.powerj.cmdlets;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;

/** {@code mkdir}: creates folders and emits their {@link FileEntry} (specification FR-72). */
@CmdletInfo(name = "mkdir", category = "Files", summary = "Creates folders",
        examples = {"mkdir build", "mkdir -p src/main/java", "mkdir -n out/a out/b"})
public final class MakeDirectory implements Cmdlet<MakeDirectory.Params, Object, Object> {

    /** Parameters of {@code mkdir}. */
    public record Params(
            @Option(position = 0, description = "Folders to create")
            List<String> paths,
            @Option(shortName = 'p', description = "Creates the missing parent folders; an existing folder is not an error")
            boolean parents,
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
            throw new IllegalArgumentException(Messages.get("fileops.path.expected", "mkdir"));
        }
        for (String argument : p.paths()) {
            Path path = FileOps.path(argument, context);
            if (path != null) {
                make(p, path, context);
            }
        }
    }

    @Override
    public void process(Params p, Object input, CmdletContext<Object> context) {
        Path path = files.get(context).file(input, context);
        if (path != null) {
            make(p, path.toAbsolutePath().normalize(), context);
        }
    }

    @Override
    public void end(Params p, CmdletContext<Object> context) {
        files.remove(context);
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
