package io.powerj.cmdlets;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.fs.FileWalker;
import io.powerj.fs.Globs;

/** {@code ls}: lists files as {@link FileEntry} objects (specification FR-35). */
@CmdletInfo(name = "ls", preview = true, category = "Files", summary = "Lists files and directories",
        examples = {"ls", "ls -r --filter *.java", "ls -r -p 16 C:\\dev", "ls C:\\Windows -d", "(ls).name", "$f = ls; $f[0].size"})
public final class Ls implements Cmdlet<Ls.Params, Void, FileEntry> {

    /** Parameters of {@code ls}. */
    public record Params(
            @Option(position = 0, description = "Directories or files to list (wildcards * and ? accepted); default: current directory")
            List<String> paths,
            @Option(shortName = 'a', description = "Includes hidden files")
            boolean all,
            @Option(shortName = 'r', description = "Walks subdirectories")
            boolean recurse,
            @Option(shortName = 'f', description = "Keeps only names matching the pattern (e.g. *.java)")
            String filter,
            @Option(shortName = 'd', description = "Directories only")
            boolean dirs,
            @Option(description = "Files only")
            boolean files,
            @Option(shortName = 'p', description = "Walks the tree with n directories and entries read at the same time, "
                    + "in virtual threads (order free)")
            Integer parallel) {
    }

    private static final Comparator<Path> DIRECTORIES_FIRST = Comparator
            .comparing((Path p) -> !Files.isDirectory(p))
            .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT));

    @Override
    public void begin(Params params, CmdletContext<FileEntry> context) {
        if (params.dirs() && params.files()) {
            throw new IllegalArgumentException(Messages.get("ls.dirs.files.incompatible"));
        }
        FileOps.parallelism(params.parallel()); // refuses a count below 1 before anything is listed
        Optional<PathMatcher> filter = Optional.ofNullable(params.filter()).map(Globs::matcher);
        List<String> paths = params.paths().isEmpty() ? List.of(".") : params.paths();
        for (String path : paths) {
            list(path, params, filter, context);
        }
    }

    private void list(String argument, Params params, Optional<PathMatcher> filter, CmdletContext<FileEntry> context) {
        Path path;
        try {
            path = context.currentDirectory().resolve(argument).normalize();
        } catch (InvalidPathException _) {
            listWildcard(argument, params, filter, context);
            return;
        }
        if (Globs.isPattern(argument)) {
            listWildcard(argument, params, filter, context);
        } else if (Files.isDirectory(path)) {
            listDirectory(path, params, filter, context);
        } else if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            entry(path).filter(e -> keep(e, params, filter)).ifPresent(context::emit);
        } else {
            context.error(Messages.get("ls.not.found", argument));
        }
    }

    /** {@code ls *.txt} or {@code ls src/*.java}: wildcard on the last element of the path. */
    private void listWildcard(String argument, Params params, Optional<PathMatcher> filter,
                              CmdletContext<FileEntry> context) {
        int separator = Math.max(argument.lastIndexOf('/'), argument.lastIndexOf('\\'));
        String dir = separator < 0 ? "." : argument.substring(0, separator + 1);
        PathMatcher pattern = Globs.matcher(argument.substring(separator + 1));
        Path base = context.currentDirectory().resolve(dir).normalize();
        List<FileEntry> matches = new ArrayList<>();
        walker(params, 1, 1).walk(base,
                (path, attributes) -> pattern.matches(path.getFileName()) ? entry(path, attributes) : null,
                matches::add, error -> FileArguments.report(error, context));
        for (FileEntry match : matches) {
            if (keep(match, params, filter)) {
                context.emit(match);
            }
            if (params.recurse() && Files.isDirectory(match.path(), LinkOption.NOFOLLOW_LINKS)) {
                listDirectory(match.path(), params, filter, context);
            }
        }
        if (matches.isEmpty()) {
            context.error(Messages.get("ls.no.match", argument));
        }
    }

    /** Lazy walk (FR-35): each entry is emitted as soon as it is read; symbolic links are not followed. */
    private void listDirectory(Path dir, Params params, Optional<PathMatcher> filter, CmdletContext<FileEntry> context) {
        int parallelism = FileOps.parallelism(params.parallel());
        walker(params, params.recurse() ? Integer.MAX_VALUE : 1, parallelism).walk(dir, Ls::entry, entry -> {
            if (keep(entry, params, filter)) {
                context.emit(entry);
            }
        }, error -> FileArguments.report(error, context));
    }

    /** With {@code parallelism > 1}, the order of the entries is free (FR-35). */
    private static FileWalker walker(Params params, int maxDepth, int parallelism) {
        return FileWalker.builder().maxDepth(maxDepth).includeHidden(params.all()).order(DIRECTORIES_FIRST)
                .parallelism(parallelism).build();
    }

    private static boolean keep(FileEntry entry, Params params, Optional<PathMatcher> filter) {
        if (params.dirs() && !entry.dir() || params.files() && entry.dir()) {
            return false;
        }
        return filter.map(f -> f.matches(entry.path().getFileName())).orElse(true);
    }

    private static Optional<FileEntry> entry(Path path) {
        return FileEntries.of(path);
    }

    private static FileEntry entry(Path path, BasicFileAttributes attributes) {
        return FileEntries.of(path, attributes);
    }
}
