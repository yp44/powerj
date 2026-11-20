package io.powerj.cmdlets;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.regex.PatternSyntaxException;

import io.powerj.api.CmdletContext;
import io.powerj.api.UsageException;
import io.powerj.fs.FileTrees;

/** What the file operations share (specification FR-72): paths, error messages, copy and removal of trees. */
final class FileOps {

    static final PipelineFiles.Keys KEYS = new PipelineFiles.Keys("files.not.a.file", "files.property.not.a.file",
            "files.invalid.path");

    private FileOps() {
    }

    /**
     * Path arguments of a file operation: the words, and at most one block that gives the path of each pipeline
     * object ({@code ls ../src --files | touch { $_.name }}).
     */
    record Arguments(List<Object> words, io.powerj.api.ScriptBlock block) {
    }

    /**
     * Splits the arguments into words and block.
     *
     * @throws IllegalArgumentException for two blocks, a block with {@code --property}, or a block without pipeline
     *                                  input (it has no object to evaluate)
     */
    static Arguments arguments(List<Object> paths, String property, CmdletContext<?> context) {
        List<Object> words = new ArrayList<>();
        io.powerj.api.ScriptBlock block = null;
        for (Object path : paths) {
            if (path instanceof io.powerj.api.ScriptBlock b) {
                if (block != null) {
                    throw new UsageException(Messages.get("fileops.block.twice"));
                }
                block = b;
            } else {
                words.add(path);
            }
        }
        if (block != null && property != null) {
            throw new UsageException(Messages.get("fileops.block.property"));
        }
        if (block != null && !context.hasInput()) {
            throw new UsageException(Messages.get("fileops.block.needs.input", block.source()));
        }
        return new Arguments(words, block);
    }

    /**
     * Paths of an argument: the path itself, or the matches of a wildcard on its last element. Wildcards keep case unless
     * the session says otherwise ({@link FileArguments#ignoreCase}): {@code rm README*} does not remove {@code readme.md}
     * (review C-22) until the variable asks for it, and {@code rm -n} shows the list first.
     */
    static List<Path> expand(Object argument, CmdletContext<?> context) {
        return expand(argument, context, false);
    }

    /**
     * Paths of an argument; with {@code quiet}, a wildcard that matches nothing is not an error ({@code rm -f *.tmp} in a
     * folder without a {@code .tmp}, as for POSIX {@code rm -f}).
     */
    static List<Path> expand(Object argument, CmdletContext<?> context, boolean quiet) {
        return expand(argument, FileArguments.isWildcard(argument) && FileArguments.ignoreCase(context), quiet, context);
    }

    /** Paths of an argument, with the given case rule for the wildcard. */
    static List<Path> expand(Object argument, boolean ignoreCase, CmdletContext<?> context) {
        return expand(argument, ignoreCase, false, context);
    }

    private static List<Path> expand(Object argument, boolean ignoreCase, boolean quiet, CmdletContext<?> context) {
        try {
            return FileArguments.expand(argument, false, ignoreCase, context, error -> FileArguments.report(error, context),
                    quiet ? null : "files.no.match");
        } catch (InvalidPathException e) {
            context.error(Messages.get("files.invalid.path", FileArguments.text(argument), e.getMessage()));
            return List.of();
        } catch (PatternSyntaxException e) {
            context.error(Messages.get("fileops.invalid.pattern", FileArguments.text(argument), e.getDescription()));
            return List.of();
        }
    }

    /** Path of an argument taken as is (a name to create, a destination), or {@code null} after an error. */
    static Path path(String argument, CmdletContext<?> context) {
        try {
            return context.currentDirectory().resolve(argument).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            context.error(Messages.get("files.invalid.path", argument, e.getMessage()));
            return null;
        }
    }

    static boolean exists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    /** A real folder: a link to a folder is not one (a source and what {@code rm} removes are never followed). */
    static boolean isDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * A folder, or a link to one: where a destination or its parent is concerned, a link to a folder is a folder
     * ({@code cp a.txt alias/}, and on macOS {@code /tmp} is a link), audit v2 R-08.
     */
    static boolean isFolderOrLinkToFolder(Path path) {
        return Files.isDirectory(path);
    }

    static boolean isEmptyDirectory(Path path) throws IOException {
        try (var entries = Files.list(path)) {
            return entries.findAny().isEmpty();
        }
    }

    /** Reports a failed operation on {@code path} as a non-blocking error with a readable reason. */
    static void report(IOException e, Path path, CmdletContext<?> context) {
        context.error(switch (e) {
            case NoSuchFileException _ -> Messages.get("files.not.found", path);
            case AccessDeniedException _ -> Messages.get("files.access.denied", path);
            case FileAlreadyExistsException _ -> Messages.get("fileops.exists", path);
            case DirectoryNotEmptyException _ -> Messages.get("fileops.not.empty", path);
            default -> Messages.get("fileops.failed", path, e.getMessage() == null ? e.toString() : e.getMessage());
        });
    }

    /**
     * Removes a tree, content first, with up to {@code parallelism} entries removed at the same time (specification
     * FR-72b); links are not followed. {@code removed} receives each entry once it is removed (or would be, with
     * {@code dryRun}), with its {@link FileEntry} when {@code describe} is set ({@code null} otherwise). An entry that
     * cannot be read or removed is reported. Returns {@code true} if everything was removed.
     */
    static boolean deleteTree(Path root, int parallelism, boolean dryRun, boolean describe, CmdletContext<?> context,
                              BiConsumer<Path, FileEntry> removed) {
        return FileTrees.delete(root, parallelism, dryRun,
                entry -> removed.accept(entry.path(),
                        describe ? FileEntries.of(entry.path(), entry.attributes()) : null),
                (path, e) -> report(e, path, context));
    }

    /**
     * Copies a file, a link or a tree to {@code target}, keeping the attributes, with up to {@code parallelism}
     * entries copied at the same time (specification FR-72b); links are copied as links. With {@code force},
     * existing files are replaced and existing folders are merged. Returns {@code true} if everything was copied.
     */
    static boolean copyTree(Path source, Path target, int parallelism, boolean force, CmdletContext<?> context) {
        return FileTrees.copy(source, target, parallelism, force, (path, e) -> report(e, path, context));
    }

    /** Checks the value of {@code -p/--parallel}: {@code null} is sequential. */
    static int parallelism(Integer parallel) {
        if (parallel != null && parallel < 1) {
            throw new UsageException(Messages.get("fileops.parallel.invalid", parallel));
        }
        return parallel == null ? 1 : parallel;
    }
}
