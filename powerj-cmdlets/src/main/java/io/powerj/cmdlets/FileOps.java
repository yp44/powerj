package io.powerj.cmdlets;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import io.powerj.api.CmdletContext;
import io.powerj.fs.WalkError;

/** What the file operations share (specification FR-72): paths, error messages, copy and removal of trees. */
final class FileOps {

    static final PipelineFiles.Keys KEYS = new PipelineFiles.Keys("fileops.not.a.file", "fileops.property.not.a.file",
            "fileops.invalid.path");

    private FileOps() {
    }

    /** Paths of an argument: the path itself, or the matches of a wildcard on its last element. */
    static List<Path> expand(String argument, CmdletContext<?> context) {
        try {
            return FileArguments.expand(argument, false, context, error -> report(error, context), "fileops.no.match");
        } catch (InvalidPathException e) {
            context.error(Messages.get("fileops.invalid.path", argument, e.getMessage()));
            return List.of();
        }
    }

    /** Path of an argument taken as is (a name to create, a destination), or {@code null} after an error. */
    static Path path(String argument, CmdletContext<?> context) {
        try {
            return context.currentDirectory().resolve(argument).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            context.error(Messages.get("fileops.invalid.path", argument, e.getMessage()));
            return null;
        }
    }

    static boolean exists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    /** A real folder: a link to a folder is not one (links are never followed). */
    static boolean isDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    static boolean isEmptyDirectory(Path path) throws IOException {
        try (var entries = Files.list(path)) {
            return entries.findAny().isEmpty();
        }
    }

    /** Reports a failed operation on {@code path} as a non-blocking error with a readable reason. */
    static void report(IOException e, Path path, CmdletContext<?> context) {
        context.error(switch (e) {
            case NoSuchFileException _ -> Messages.get("fileops.not.found", path);
            case AccessDeniedException _ -> Messages.get("fileops.access.denied", path);
            case FileAlreadyExistsException _ -> Messages.get("fileops.exists", path);
            case DirectoryNotEmptyException _ -> Messages.get("fileops.not.empty", path);
            default -> Messages.get("fileops.failed", path, e.getMessage() == null ? e.toString() : e.getMessage());
        });
    }

    static void report(WalkError error, CmdletContext<?> context) {
        context.error(switch (error.kind()) {
            case ACCESS_DENIED -> Messages.get("fileops.access.denied", error.path());
            case NOT_FOUND -> Messages.get("fileops.not.found", error.path());
            case IO -> Messages.get("fileops.failed", error.path(), error.message());
        });
    }

    /** Ctrl+C between two entries of a tree. */
    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException();
        }
    }

    /**
     * Entries of a tree, content before folders (the order of a removal); links are not followed. An entry that
     * cannot be read is reported and left out.
     */
    static List<Path> depthFirst(Path root, CmdletContext<?> context) {
        List<Path> entries = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    checkCancelled();
                    entries.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    report(e, file, context);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                    if (e != null) {
                        report(e, dir, context);
                    }
                    entries.add(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            report(e, root, context);
        }
        return entries;
    }

    /**
     * Removes a tree, content first. {@code removed} receives each entry once it is removed (or would be, with
     * {@code dryRun}), with its {@link FileEntry} read just before the removal when {@code describe} is set
     * ({@code null} otherwise). Returns {@code true} if everything was removed.
     */
    static boolean deleteTree(Path root, boolean dryRun, boolean describe, CmdletContext<?> context,
                              java.util.function.BiConsumer<Path, FileEntry> removed) {
        boolean complete = true;
        for (Path entry : depthFirst(root, context)) {
            checkCancelled();
            if (dryRun) {
                removed.accept(entry, null);
                continue;
            }
            FileEntry description = describe ? FileEntries.of(entry, LinkOption.NOFOLLOW_LINKS).orElse(null) : null;
            try {
                Files.delete(entry);
                removed.accept(entry, description);
            } catch (IOException e) {
                report(e, entry, context);
                complete = false;
            }
        }
        return complete;
    }

    /**
     * Copies a file, a link or a tree to {@code target}, keeping the attributes; links are copied as links.
     * With {@code force}, existing files are replaced and existing folders are merged. Returns {@code true} if
     * everything was copied.
     */
    static boolean copyTree(Path source, Path target, boolean force, CmdletContext<?> context) {
        var options = force
                ? new java.nio.file.CopyOption[] {StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS,
                        StandardCopyOption.REPLACE_EXISTING}
                : new java.nio.file.CopyOption[] {StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS};
        if (!isDirectory(source)) {
            try {
                Files.copy(source, target, options);
                return true;
            } catch (IOException e) {
                report(e, target, context);
                return false;
            }
        }
        boolean[] complete = {true};
        try {
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    checkCancelled();
                    Path copy = target.resolve(source.relativize(dir).toString());
                    try {
                        if (!(force && isDirectory(copy))) {
                            Files.copy(dir, copy, StandardCopyOption.COPY_ATTRIBUTES);
                        }
                        return FileVisitResult.CONTINUE;
                    } catch (IOException e) {
                        report(e, copy, context);
                        complete[0] = false;
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    checkCancelled();
                    Path copy = target.resolve(source.relativize(file).toString());
                    try {
                        Files.copy(file, copy, options);
                    } catch (IOException e) {
                        report(e, copy, context);
                        complete[0] = false;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    report(e, file, context);
                    complete[0] = false;
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            report(e, source, context);
            return false;
        }
        return complete[0];
    }
}
