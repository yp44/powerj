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
import io.powerj.api.UsageException;
import io.powerj.fs.FileWalker;
import io.powerj.fs.GlobArgument;
import io.powerj.fs.Globs;

/** {@code ls}: lists files as {@link FileEntry} objects (specification FR-35). */
@CmdletInfo(name = "ls", preview = true, category = "Files", summary = "Lists files and directories",
        examples = {"ls", "ls -r --filter *.java", "ls -r -p 16 C:\\dev", "ls C:\\Windows -d", "(ls).name", "$f = ls; $f[0].size"})
public final class Ls implements Cmdlet<Ls.Params, Void, FileEntry> {

    /** Parameters of {@code ls}. */
    public record Params(
            @Option(position = 0, path = true, description = "Directories or files to list (wildcards * and ? accepted); default: current directory")
            List<Object> paths,
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
            Integer parallel,
            @Option(description = "Maximum depth of the walk (1: only the entries of the directory); implies --recurse")
            Integer maxDepth) {
    }

    private static final Comparator<Path> DIRECTORIES_FIRST = Comparator
            .comparing((Path p) -> !Files.isDirectory(p))
            .thenComparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT));

    @Override
    public void begin(Params params, CmdletContext<FileEntry> context) {
        if (params.dirs() && params.files()) {
            throw new UsageException(Messages.get("ls.dirs.files.incompatible"));
        }
        Parallelism.of(params.parallel()); // refuses a count below 1 before anything is listed
        if (params.maxDepth() != null && params.maxDepth() < 1) {
            throw new UsageException(Messages.get("files.maxDepth.invalid", params.maxDepth()));
        }
        Optional<PathMatcher> filter = Optional.ofNullable(params.filter())
                .map(pattern -> Globs.matcher(pattern, FileArguments.ignoreCase(context)));
        List<Object> paths = params.paths().isEmpty() ? List.of(".") : params.paths();
        for (Object path : paths) {
            list(path, params, filter, context);
        }
    }

    private void list(Object argument, Params params, Optional<PathMatcher> filter, CmdletContext<FileEntry> context) {
        String text = FileArguments.text(argument);
        Path path;
        try {
            path = context.currentDirectory().resolve(text).normalize();
        } catch (InvalidPathException _) {
            if (argument instanceof GlobArgument && !FileArguments.isWildcard(argument)) {
                context.error(Messages.get("files.not.found", text)); // a quoted name that this system cannot spell
            } else {
                listWildcard(argument, params, filter, context);
            }
            return;
        }
        if (FileArguments.isWildcard(argument)) {
            listWildcard(argument, params, filter, context);
        } else if (Files.isDirectory(path)) {
            listDirectory(path, depth(params), params, filter, context);
        } else if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            entry(path).filter(e -> keep(e, params, filter)).ifPresent(context::emit);
        } else {
            context.error(Messages.get("files.not.found", text));
        }
    }

    /** {@code ls *.txt} or {@code ls src/*.java}: wildcard on the last element of the path. */
    private void listWildcard(Object argument, Params params, Optional<PathMatcher> filter,
                              CmdletContext<FileEntry> context) {
        FileArguments.Split split = FileArguments.split(argument);
        PathMatcher pattern = Globs.matcher(split.namePattern(), FileArguments.ignoreCase(context));
        Path base = context.currentDirectory().resolve(split.directory()).normalize();
        List<FileEntry> matches = new ArrayList<>();
        walker(params, 1, 1).walk(base,
                (path, attributes) -> pattern.matches(path.getFileName()) ? entry(path, attributes) : null,
                matches::add, error -> FileArguments.report(error, context));
        for (FileEntry match : matches) {
            if (keep(match, params, filter)) {
                context.emit(match);
            }
            // a match is at depth 1: its entries are one level deeper
            int below = depth(params) == Integer.MAX_VALUE ? Integer.MAX_VALUE : depth(params) - 1;
            if (below >= 1 && Files.isDirectory(match.path(), LinkOption.NOFOLLOW_LINKS)) {
                listDirectory(match.path(), below, params, filter, context);
            }
        }
        if (matches.isEmpty()) {
            context.error(Messages.get("files.no.match", FileArguments.text(argument)));
        }
    }

    /** Depth of the walk below a listed directory: 1 without {@code -r}, the whole tree with it, or {@code --max-depth}. */
    private static int depth(Params params) {
        if (params.maxDepth() != null) {
            return params.maxDepth();
        }
        return params.recurse() ? Integer.MAX_VALUE : 1;
    }

    /** Lazy walk (FR-35): each entry is emitted as soon as it is read; symbolic links are not followed. */
    private void listDirectory(Path dir, int maxDepth, Params params, Optional<PathMatcher> filter,
                               CmdletContext<FileEntry> context) {
        int parallelism = Parallelism.of(params.parallel());
        walker(params, maxDepth, parallelism).walk(dir, Ls::entry, entry -> {
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
