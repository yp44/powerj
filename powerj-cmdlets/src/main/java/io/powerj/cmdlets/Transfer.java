package io.powerj.cmdlets;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import io.powerj.api.CmdletContext;

/**
 * One execution of {@code cp} or {@code mv} (specification FR-72): sources from the arguments and the pipeline, the
 * last argument being the destination; the copies or moves are done when the input ends.
 */
final class Transfer {

    /** Settings of the command. */
    record Options(boolean move, boolean recurse, boolean force, boolean verbose, boolean dryRun,
                   boolean allowProtected) {
    }

    private final Options options;
    private final PipelineFiles files;
    private final String destination;
    private final List<Path> sources = new ArrayList<>();

    /** Reads the arguments: all but the last one are sources (wildcards accepted), the last one is the destination. */
    Transfer(Options options, List<String> paths, String property, CmdletContext<?> context) {
        this.options = options;
        if (paths.isEmpty() || (paths.size() == 1 && !context.hasInput())) {
            throw new IllegalArgumentException(Messages.get("fileops.destination.expected", options.move() ? "mv" : "cp"));
        }
        this.files = new PipelineFiles(property, context, FileOps.KEYS);
        this.destination = paths.getLast();
        for (String argument : paths.subList(0, paths.size() - 1)) {
            sources.addAll(FileOps.expand(argument, context));
        }
    }

    void add(Object input, CmdletContext<?> context) {
        Path source = files.file(input, context);
        if (source != null) {
            sources.add(source);
        }
    }

    /** Copies or moves each source into the destination folder, or to the destination name for a single source. */
    void run(CmdletContext<Object> context) {
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
        ProtectedPaths protectedPaths = options.move() && !options.allowProtected()
                ? ProtectedPaths.load(context.configDirectory(), context.environment(), context.currentDirectory())
                : null;
        for (Path source : sources) {
            Path absolute = source.toAbsolutePath().normalize();
            Path name = absolute.getFileName();
            transfer(absolute, into && name != null ? target.resolve(name.toString()) : target, protectedPaths, context);
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
        if (target.equals(source)) {
            context.error(Messages.get("fileops.same.file", source));
            return;
        }
        if (directory && target.startsWith(source)) {
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
                : FileOps.copyTree(source, target, options.force(), context);
        if (done && options.verbose()) {
            FileEntries.of(target, LinkOption.NOFOLLOW_LINKS).ifPresent(context::emit);
        }
    }

    /**
     * Moves atomically when possible; otherwise (another file system, a folder merged with {@code -f}) copies, then
     * removes the source only if the whole copy succeeded.
     */
    private boolean move(Path source, Path target, CmdletContext<Object> context) {
        CopyOption[] replace = options.force()
                ? new CopyOption[] {StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS}
                : new CopyOption[] {LinkOption.NOFOLLOW_LINKS};
        try {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                return true;
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(source, target, replace);
                return true;
            }
        } catch (DirectoryNotEmptyException e) {
            if (!FileOps.copyTree(source, target, options.force(), context)) {
                return false; // the source is kept
            }
            return FileOps.deleteTree(source, false, false, context, (path, entry) -> { });
        } catch (IOException e) {
            FileOps.report(e, source, context);
            return false;
        }
    }
}
