package io.powerj.cmdlets;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import io.powerj.api.CmdletContext;
import io.powerj.api.ScriptBlock;

/**
 * One execution of {@code cp} or {@code mv} (specification FR-72): sources from the arguments and the pipeline, the
 * last argument being the destination; the copies or moves are done when the input ends.
 */
final class Transfer {

    /** Settings of the command. */
    record Options(boolean move, boolean recurse, int parallelism, boolean force, boolean verbose, boolean dryRun,
                   boolean allowProtected) {
    }

    /** A source, and the object that designated it ({@code $_} of a destination block). */
    private record Source(Path path, Object object) {
    }

    private final Options options;
    private final PipelineFiles files;
    /** Destination of all the sources, or {@code null} with a destination block. */
    private final String destination;
    /** Block that gives the destination of each source ({@code cp *.txt { $_.name + ".bak" }}), or {@code null}. */
    private final ScriptBlock destinationBlock;
    private final List<Source> sources = new ArrayList<>();

    /**
     * Reads the arguments: all but the last one are sources (wildcards accepted), the last one is the destination,
     * a path or a block evaluated for each source.
     */
    Transfer(Options options, List<Object> paths, String property, CmdletContext<?> context) {
        this.options = options;
        if (paths.isEmpty() || (paths.size() == 1 && !context.hasInput())) {
            throw new IllegalArgumentException(Messages.get("fileops.destination.expected", options.move() ? "mv" : "cp"));
        }
        for (Object path : paths.subList(0, paths.size() - 1)) {
            if (path instanceof ScriptBlock) {
                throw new IllegalArgumentException(Messages.get("fileops.block.destination"));
            }
        }
        this.files = new PipelineFiles(property, context, FileOps.KEYS);
        Object last = paths.getLast();
        this.destinationBlock = last instanceof ScriptBlock block ? block : null;
        this.destination = destinationBlock == null ? String.valueOf(last) : null;
        for (Object argument : paths.subList(0, paths.size() - 1)) {
            for (Path path : FileOps.expand(String.valueOf(argument), context)) {
                sources.add(new Source(path, path));
            }
        }
    }

    void add(Object input, CmdletContext<?> context) {
        Path source = files.file(input, context);
        if (source != null) {
            sources.add(new Source(source, input));
        }
    }

    /** Copies or moves each source into the destination folder, or to the destination name for a single source. */
    void run(CmdletContext<Object> context) {
        ProtectedPaths protectedPaths = options.move() && !options.allowProtected()
                ? ProtectedPaths.load(context.configDirectory(), context.environment(), context.currentDirectory())
                : null;
        if (destinationBlock != null) {
            runEach(protectedPaths, context);
            return;
        }
        Path target = FileOps.path(destination, context);
        if (target == null || sources.isEmpty()) {
            return;
        }
        boolean folder = destination.endsWith("/") || destination.endsWith("\\"); // cp a.txt backup/
        boolean into = FileOps.isDirectory(target) || sources.size() > 1 || folder;
        if (into && !FileOps.isDirectory(target)) {
            context.error(Messages.get("fileops.not.directory", target));
            return;
        }
        for (Source source : sources) {
            Path absolute = source.path().toAbsolutePath().normalize();
            Path name = absolute.getFileName();
            transfer(absolute, into && name != null ? target.resolve(name.toString()) : target, protectedPaths, context);
        }
    }

    /**
     * Destination block: evaluated for each source, with {@code $_} the object received (or the {@link FileEntry} of
     * a source argument). A folder result receives the source under its name; {@code null} skips the source.
     */
    private void runEach(ProtectedPaths protectedPaths, CmdletContext<Object> context) {
        for (Source source : sources) {
            Path absolute = source.path().toAbsolutePath().normalize();
            Object current = source.object() instanceof Path path
                    ? FileEntries.of(path, LinkOption.NOFOLLOW_LINKS).<Object>map(e -> e).orElse(path) : source.object();
            Object value;
            try {
                value = destinationBlock.invoke(current);
            } catch (CancellationException e) {
                throw e;
            } catch (RuntimeException e) {
                context.error(Messages.get("pipeline.object.skipped", e.getMessage(), Where.abbreviate(current)));
                continue;
            }
            if (value == null) {
                continue;
            }
            Path target;
            try {
                target = value instanceof String text ? context.currentDirectory().resolve(text)
                        : FileArguments.file(value, context);
            } catch (InvalidPathException e) {
                context.error(Messages.get("fileops.invalid.path", value, e.getMessage()));
                continue;
            }
            if (target == null) {
                context.error(Messages.get("fileops.block.not.a.file", destinationBlock.source(), Where.abbreviate(value)));
                continue;
            }
            target = target.toAbsolutePath().normalize();
            Path name = absolute.getFileName();
            transfer(absolute, FileOps.isDirectory(target) && name != null ? target.resolve(name.toString()) : target,
                    protectedPaths, context);
        }
    }

    private void transfer(Path source, Path target, ProtectedPaths protectedPaths, CmdletContext<Object> context) {
        if (!FileOps.exists(source)) {
            context.error(Messages.get("fileops.not.found", source));
            return;
        }
        if (protectedPaths != null) {
            var refusal = protectedPaths.refusal(source);
            if (refusal.isPresent()) {
                context.error(refusal.get());
                return;
            }
        }
        boolean directory = FileOps.isDirectory(source);
        if (directory && !options.move() && !options.recurse()) {
            context.error(Messages.get("fileops.is.directory", source));
            return;
        }
        // Compared as the file system sees them: through an alias or a link, a folder is still copied into itself.
        Path realTarget = RealPaths.of(target);
        if (realTarget.equals(RealPaths.of(source))) {
            context.error(Messages.get("fileops.same.file", source));
            return;
        }
        if (directory && realTarget.startsWith(RealPaths.ofFolder(source))) {
            context.error(Messages.get("fileops.into.itself", source, target));
            return;
        }
        if (FileOps.exists(target) && !options.force()) {
            context.error(Messages.get("fileops.exists", target));
            return;
        }
        Path parent = target.getParent();
        if (parent != null && !FileOps.isDirectory(parent)) {
            context.error(Messages.get("fileops.parent.missing", target));
            return;
        }
        if (options.dryRun()) {
            context.emit(new FileOperation(options.move() ? "move" : "copy", source, target));
            return;
        }
        boolean done = options.move() ? move(source, target, context)
                : FileOps.copyTree(source, target, options.parallelism(), options.force(), context);
        if (done && options.verbose()) {
            FileEntries.of(target, LinkOption.NOFOLLOW_LINKS).ifPresent(context::emit);
        }
    }

    /**
     * Moves atomically when possible; otherwise (another file system, a folder merged with {@code -f}) copies, then
     * removes the source only if the whole copy succeeded.
     */
    boolean move(Path source, Path target, CmdletContext<Object> context) {
        CopyOption[] replace = options.force()
                ? new CopyOption[] {StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS}
                : new CopyOption[] {LinkOption.NOFOLLOW_LINKS};
        try {
            if (options.force()) {
                // Only with -f: an atomic move replaces what is at the destination, without a word, on Unix
                // (rename), including a file that appeared since the check (review C-21).
                try {
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                    return true;
                } catch (AtomicMoveNotSupportedException _) {
                    // another file system: the plain move below
                }
            }
            Files.move(source, target, replace); // without REPLACE_EXISTING it fails if the destination exists
            return true;
        } catch (DirectoryNotEmptyException e) {
            if (!FileOps.copyTree(source, target, 1, options.force(), context)) {
                return false; // the source is kept
            }
            return FileOps.deleteTree(source, 1, false, false, context, (path, entry) -> { });
        } catch (FileAlreadyExistsException e) {
            FileOps.report(e, target, context);
            return false;
        } catch (IOException e) {
            FileOps.report(e, source, context);
            return false;
        }
    }
}
