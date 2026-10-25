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
import java.util.regex.PatternSyntaxException;

import io.powerj.api.CmdletContext;
import io.powerj.fs.FileTrees;
import io.powerj.fs.WalkError;

/** What the file operations share (specification FR-72): paths, error messages, copy and removal of trees. */
final class FileOps {

    static final PipelineFiles.Keys KEYS = new PipelineFiles.Keys("fileops.not.a.file", "fileops.property.not.a.file",
            "fileops.invalid.path");

    private FileOps() {
    }

    /**
     * Path arguments of a file operation: the words, and at most one block that gives the path of each pipeline
     * object ({@code ls ../src --files | touch { $_.name }}).
     */
    record Arguments(List<String> words, io.powerj.api.ScriptBlock block) {
    }

    /**
     * Splits the arguments into words and block.
     *
     * @throws IllegalArgumentException for two blocks, a block with {@code --property}, or a block without pipeline
     *                                  input (it has no object to evaluate)
     */
    static Arguments arguments(List<Object> paths, String property, CmdletContext<?> context) {
        List<String> words = new ArrayList<>();
        io.powerj.api.ScriptBlock block = null;
        for (Object path : paths) {
            if (path instanceof io.powerj.api.ScriptBlock b) {
                if (block != null) {
                    throw new IllegalArgumentException(Messages.get("fileops.block.twice"));
                }
                block = b;
            } else {
                words.add(String.valueOf(path));
            }
        }
        if (block != null && property != null) {
            throw new IllegalArgumentException(Messages.get("fileops.block.property"));
        }
        if (block != null && !context.hasInput()) {
            throw new IllegalArgumentException(Messages.get("fileops.block.needs.input", block.source()));
        }
        return new Arguments(words, block);
    }

    /**
     * Wildcards of the commands that change files ignore case where file names do (Windows) and only there:
     * {@code rm README*} must not remove {@code readme.md} on Linux or macOS, as in a shell there (review C-22).
     * {@code ls readme* | rm} is the way to ask for the other rule, with the list in plain sight.
     */
    static final boolean WILDCARDS_IGNORE_CASE = System.getProperty("os.name", "").startsWith("Windows");

    /** Paths of an argument: the path itself, or the matches of a wildcard on its last element. */
    static List<Path> expand(String argument, CmdletContext<?> context) {
        return expand(argument, WILDCARDS_IGNORE_CASE, context);
    }

    /** Paths of an argument, with the given case rule for the wildcard. */
    static List<Path> expand(String argument, boolean ignoreCase, CmdletContext<?> context) {
        try {
            return FileArguments.expand(argument, false, ignoreCase, context, error -> report(error, context),
                    "fileops.no.match");
        } catch (InvalidPathException e) {
            context.error(Messages.get("fileops.invalid.path", argument, e.getMessage()));
            return List.of();
        } catch (PatternSyntaxException e) {
            context.error(Messages.get("fileops.invalid.pattern", argument, e.getDescription()));
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

    /**
     * Removes a tree, content first, with up to {@code parallelism} entries removed at the same time (specification
     * FR-72b); links are not followed. {@code removed} receives each entry once it is removed (or would be, with
     * {@code dryRun}), with its {@link FileEntry} when {@code describe} is set ({@code null} otherwise). An entry that
     * cannot be read or removed is reported. Returns {@code true} if everything was removed.
     */
    static boolean deleteTree(Path root, int parallelism, boolean dryRun, boolean describe, CmdletContext<?> context,
                              java.util.function.BiConsumer<Path, FileEntry> removed) {
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
            throw new IllegalArgumentException(Messages.get("fileops.parallel.invalid", parallel));
        }
        return parallel == null ? 1 : parallel;
    }
}
