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
import io.powerj.api.UsageException;
import io.powerj.fs.FileWalker;
import io.powerj.fs.GlobArgument;
import io.powerj.fs.Globs;
import io.powerj.fs.Host;
import io.powerj.fs.WalkError;

/** File arguments shared by the cmdlets that read files ({@code grep}, {@code cat}). */
final class FileArguments {

    static final Comparator<Path> BY_NAME = Comparator.comparing(
            (Path p) -> p.getFileName().toString().toLowerCase(Locale.ROOT)).thenComparing(Path::toString);

    private FileArguments() {
    }

    /**
     * Whether the wildcards of the session ignore case: {@code true} if {@link Globs#IGNORE_CASE_VARIABLE} says so, and
     * case counts otherwise (specification FR-40). Any value other than {@code true}, {@code 1}, {@code false} or
     * {@code 0} is a usage error.
     */
    static boolean ignoreCase(CmdletContext<?> context) {
        String value = context.environment().get(Globs.IGNORE_CASE_VARIABLE);
        try {
            return Globs.ignoreCase(value);
        } catch (IllegalArgumentException _) {
            throw new UsageException(Messages.get("glob.ignoreCase.invalid", Globs.IGNORE_CASE_VARIABLE, value));
        }
    }

    /** What a path argument says: the text of a word, whether or not the shell handed it as a {@link GlobArgument}. */
    static String text(Object argument) {
        return argument instanceof GlobArgument word ? word.text() : String.valueOf(argument);
    }

    /**
     * Does the argument hold a wildcard that expands? A word whose wildcards were quoted ({@link GlobArgument}) does
     * not: {@code rm "a?.txt"} is the name {@code a?.txt}. A text that is not such a word is a wildcard if it holds
     * {@code *} or {@code ?}.
     */
    static boolean isWildcard(Object argument) {
        return argument instanceof GlobArgument word ? word.hasWildcard() : Globs.isPattern(String.valueOf(argument));
    }

    /** A path argument cut after its last separator: the folder, and the pattern of the name. */
    record Split(String directory, String namePattern) {
    }

    /**
     * Cuts an argument whose last element is a wildcard. The pattern of a {@link GlobArgument} escapes what the quotes made
     * literal, so the folder is read back as text; a backslash is a separator in a plain text, and only on Windows in a
     * pattern, where it escapes.
     */
    static Split split(Object argument) {
        if (argument instanceof GlobArgument word) {
            String pattern = word.pattern();
            int separator = Host.windows() ? Math.max(pattern.lastIndexOf('/'), pattern.lastIndexOf('\\'))
                    : pattern.lastIndexOf('/');
            String directory = separator < 0 ? "." : Globs.unescape(pattern.substring(0, separator + 1));
            return new Split(directory, pattern.substring(separator + 1));
        }
        String text = String.valueOf(argument);
        int separator = Math.max(text.lastIndexOf('/'), text.lastIndexOf('\\'));
        return new Split(separator < 0 ? "." : text.substring(0, separator + 1), text.substring(separator + 1));
    }

    /**
     * Paths designated by an argument: a path, or the matches of a wildcard on its last element, in name order.
     * A wildcard without match reports the error {@code noMatchKey}. Whether names are matched without regard to case is
     * the rule of the session ({@link #ignoreCase(CmdletContext)}), read only for a wildcard.
     */
    static List<Path> expand(Object argument, boolean includeHidden, CmdletContext<?> context,
                             Consumer<WalkError> onError, String noMatchKey) {
        return expand(argument, includeHidden, isWildcard(argument) && ignoreCase(context), context, onError,
                noMatchKey);
    }

    /**
     * Like {@link #expand(Object, boolean, CmdletContext, Consumer, String)}, with the case rule of the wildcard:
     * {@code ignoreCase} {@code false} matches the names in the case in which they are written.
     */
    static List<Path> expand(Object argument, boolean includeHidden, boolean ignoreCase, CmdletContext<?> context,
                             Consumer<WalkError> onError, String noMatchKey) {
        String text = text(argument);
        boolean quoted = argument instanceof GlobArgument;
        if (!isWildcard(argument)) {
            try {
                return List.of(context.currentDirectory().resolve(text).normalize());
            } catch (InvalidPathException e) {
                if (quoted) {
                    throw e; // no wildcard left to expand: a name that this system cannot spell (Windows: * and ?)
                }
                // Windows: * and ? are invalid in a path, handled as a wildcard
            }
        }
        return expandWildcard(argument, includeHidden, ignoreCase, context, onError, noMatchKey, (path, _) -> path);
    }

    /**
     * The matches of a wildcard on the last element of {@code argument}, in name order, each made by {@code visitor} from
     * the attributes that the walk read with the listing: {@code ls *.txt} builds its entries without reading a file a
     * second time, and a match that cannot be read is reported by the walk (review of #225). A wildcard without match
     * reports {@code noMatchKey}, unless it is {@code null}.
     */
    static <T> List<T> expandWildcard(Object argument, boolean includeHidden, boolean ignoreCase,
                                      CmdletContext<?> context, Consumer<WalkError> onError, String noMatchKey,
                                      FileWalker.Visitor<T> visitor) {
        Split split = split(argument);
        PathMatcher pattern = Globs.matcher(split.namePattern(), ignoreCase);
        List<T> matches = new ArrayList<>();
        FileWalker.builder().maxDepth(1).includeHidden(includeHidden).order(BY_NAME).build()
                .walk(context.currentDirectory().resolve(split.directory()).normalize(),
                        (path, attributes) -> pattern.matches(path.getFileName()) ? visitor.visit(path, attributes) : null,
                        matches::add, onError);
        if (matches.isEmpty() && noMatchKey != null) { // null: the caller does not want it said (rm -f)
            context.error(Messages.get(noMatchKey, text(argument)));
        }
        return matches;
    }

    /**
     * Like {@link #expand(Object, boolean, CmdletContext, Consumer, String)}, for a command that reads the files: a name this
     * system cannot spell (a quoted {@code ?} on Windows, a NUL) is reported as a non-blocking error and gives no path.
     */
    static List<Path> expandOrReport(Object argument, boolean includeHidden, CmdletContext<?> context,
                                     String noMatchKey) {
        try {
            return expand(argument, includeHidden, context, error -> report(error, context), noMatchKey);
        } catch (InvalidPathException e) {
            context.error(Messages.get("files.invalid.path", text(argument), e.getMessage()));
            return List.of();
        }
    }

    /**
     * Reports a failure of the walk of a folder as a non-blocking error: the one reporter of a {@link WalkError}, shared by
     * {@code ls}, {@code cat}, {@code grep}, {@code cp}, {@code mv} and {@code rm} (audit v2, C-04).
     */
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
            throw new UsageException(Messages.get(unknownKey, encoding));
        }
    }
}
