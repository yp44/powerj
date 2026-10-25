package io.powerj.cmdlets;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import io.powerj.api.CmdletContext;
import io.powerj.fs.FileWalker;
import io.powerj.fs.Globs;
import io.powerj.fs.WalkError;

/** File arguments shared by the cmdlets that read files ({@code grep}, {@code cat}). */
final class FileArguments {

    static final Comparator<Path> BY_NAME = Comparator.comparing(
            (Path p) -> p.getFileName().toString().toLowerCase(Locale.ROOT)).thenComparing(Path::toString);

    private FileArguments() {
    }

    /**
     * Paths designated by an argument: a path, or the matches of a wildcard on its last element, in name order.
     * A wildcard without match reports the error {@code noMatchKey}. Names are matched without regard to case.
     */
    static List<Path> expand(String argument, boolean includeHidden, CmdletContext<?> context,
                             Consumer<WalkError> onError, String noMatchKey) {
        return expand(argument, includeHidden, true, context, onError, noMatchKey);
    }

    /**
     * Like {@link #expand(String, boolean, CmdletContext, Consumer, String)}, with the case rule of the wildcard:
     * {@code ignoreCase} {@code false} matches the names in the case in which they are written.
     */
    static List<Path> expand(String argument, boolean includeHidden, boolean ignoreCase, CmdletContext<?> context,
                             Consumer<WalkError> onError, String noMatchKey) {
        if (!Globs.isPattern(argument)) {
            try {
                return List.of(context.currentDirectory().resolve(argument).normalize());
            } catch (InvalidPathException _) {
                // Windows: * and ? are invalid in a path, handled as a wildcard
            }
        }
        int separator = Math.max(argument.lastIndexOf('/'), argument.lastIndexOf('\\'));
        String dir = separator < 0 ? "." : argument.substring(0, separator + 1);
        PathMatcher pattern = Globs.matcher(argument.substring(separator + 1), ignoreCase);
        List<Path> matches = new ArrayList<>();
        FileWalker.builder().maxDepth(1).includeHidden(includeHidden).order(BY_NAME).build()
                .walk(context.currentDirectory().resolve(dir).normalize(),
                        (path, _) -> pattern.matches(path.getFileName()) ? path : null, matches::add, onError);
        if (matches.isEmpty()) {
            context.error(Messages.get(noMatchKey, argument));
        }
        return matches;
    }

    /** Reports a failure of the walk of a folder as a non-blocking error (shared by {@code ls}, {@code cat}, {@code grep}). */
    static void report(WalkError error, CmdletContext<?> context) {
        context.error(switch (error.kind()) {
            case ACCESS_DENIED -> Messages.get("files.access.denied", error.path());
            case NOT_FOUND -> Messages.get("files.not.found", error.path());
            case IO -> Messages.get("files.read.failed", error.path(), error.message());
        });
    }

    /** File designated by a pipeline object ({@link FileEntry}, {@link Path}, {@link File}), or {@code null}. */
    static Path file(Object input, CmdletContext<?> context) {
        return switch (input) {
            case FileEntry entry -> entry.path();
            case Path path -> context.currentDirectory().resolve(path);
            case File file -> context.currentDirectory().resolve(file.toPath());
            case null, default -> null;
        };
    }

    /** Charset named by {@code --encoding}, UTF-8 by default; an unknown name reports {@code unknownKey}. */
    static Charset charset(String encoding, String unknownKey) {
        if (encoding == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(encoding);
        } catch (IllegalCharsetNameException | UnsupportedCharsetException _) {
            throw new IllegalArgumentException(Messages.get(unknownKey, encoding));
        }
    }
}
