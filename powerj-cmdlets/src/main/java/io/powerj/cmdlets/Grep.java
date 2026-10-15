package io.powerj.cmdlets;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.SequenceInputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.ScriptBlock;
import io.powerj.api.Span;
import io.powerj.fs.FileWalker;
import io.powerj.fs.Globs;
import io.powerj.fs.WalkError;

/**
 * {@code grep}: searches a regular expression ({@link Pattern} syntax) in files, or filters the pipeline
 * objects (specification FR-64).
 * <pre>
 * grep TODO src -r                       # GrepMatch objects: path, line, text…
 * grep "version=\"(?&lt;v&gt;[^\"]+)\"" -r -p 16 | map { m -> m.named.v }
 * ls -r --filter *.java | grep TODO      # files received from the pipeline: their content is searched
 * git log | grep -i fix                  # other objects: filtered like where
 * ls | grep --property name "^a"
 * grep MAGIC data.bin --binary --encoding ISO-8859-1
 * </pre>
 */
@CmdletInfo(name = "grep", category = "Files", summary = "Searches a regular expression in files or pipeline objects",
        examples = {"grep TODO src -r", "grep -i \"error|warn\" *.log", "grep \"class (\\\\w+)\" -r -p 16 | map { m -> m.groups[1] }", "grep class\\s+\\w+ src -r",
                "grep \"version=\\\"(?<v>[^\\\"]+)\\\"\" pom.xml | map { m -> m.named.v }",
                "ls -r --filter *.java | grep TODO", "git log --oneline | grep -i fix", "ls | grep --property name \"^a\"",
                "grep MAGIC data.bin --binary --encoding ISO-8859-1"})
public final class Grep implements Cmdlet<Grep.Params, Object, Object> {

    /** Parameters of {@code grep}. */
    public record Params(
            @Option(position = 0, mandatory = true, description = "Regular expression (java.util.regex syntax)")
            String pattern,
            @Option(position = 1, description = "Files or directories to search (wildcards * and ? accepted); "
                    + "default: current directory, or the pipeline objects")
            List<String> paths,
            @Option(shortName = 'r', description = "Walks subdirectories")
            boolean recurse,
            @Option(shortName = 'p', description = "Walks the tree with n files read at the same time, in virtual threads "
                    + "(order of the files free)")
            Integer parallel,
            @Option(description = "Maximum depth of the walk (1: only the files of the directory); implies --recurse")
            Integer maxDepth,
            @Option(shortName = 'i', description = "Ignores case")
            boolean ignoreCase,
            @Option(shortName = 'v', description = "Keeps the lines (or objects) that do not match")
            boolean invert,
            @Option(shortName = 'a', description = "Includes hidden files")
            boolean all,
            @Option(description = "Charset of the files (default: UTF-8)")
            String encoding,
            @Option(description = "Property of the pipeline objects to search (e.g. name)")
            String property,
            @Option(description = "Also searches binary files (no check of the NUL byte)")
            boolean binary) {
    }

    /** Bytes read to recognize a binary file (one containing a NUL byte), skipped. */
    private static final int BINARY_PROBE = 8192;
    private static final Comparator<Path> BY_NAME = Comparator.comparing(
            (Path p) -> p.getFileName().toString().toLowerCase(Locale.ROOT)).thenComparing(Path::toString);

    /** State of each running execution: the same cmdlet can be used in several pipelines at once. */
    private final Map<Params, Search> searches = Collections.synchronizedMap(new IdentityHashMap<>());

    @Override
    public void begin(Params params, CmdletContext<Object> context) {
        Search search = new Search(params, context);
        searches.put(params, search);
        if (!params.paths().isEmpty()) {
            List<Path> roots = new ArrayList<>();
            for (String argument : params.paths()) {
                roots.addAll(roots(argument, params, context));
            }
            search.files(roots, context);
        } else if (!context.hasInput()) {
            search.files(List.of(context.currentDirectory()), context);
        }
    }

    @Override
    public void process(Params params, Object input, CmdletContext<Object> context) {
        Search search = searches.computeIfAbsent(params, p -> new Search(p, context));
        Path file = params.property() == null ? file(input, context) : null;
        if (file == null) {
            search.filter(input, context);
        } else if (params.recurse() || params.maxDepth() != null || !Files.isDirectory(file)) {
            // a directory received without -r is skipped: ls -r already emits its files
            search.files(List.of(file), context);
        }
    }

    @Override
    public void end(Params params, CmdletContext<Object> context) {
        searches.remove(params);
    }

    /** File designated by a pipeline object ({@link FileEntry}, {@link Path}, {@link File}), or {@code null}. */
    private static Path file(Object input, CmdletContext<?> context) {
        return switch (input) {
            case FileEntry entry -> entry.path();
            case Path path -> context.currentDirectory().resolve(path);
            case File file -> context.currentDirectory().resolve(file.toPath());
            case null, default -> null;
        };
    }

    /** Roots designated by an argument: a path, or the matches of a wildcard on its last element. */
    private static List<Path> roots(String argument, Params params, CmdletContext<Object> context) {
        if (!Globs.isPattern(argument)) {
            try {
                return List.of(context.currentDirectory().resolve(argument).normalize());
            } catch (InvalidPathException _) {
                // Windows: * and ? are invalid in a path, handled as a wildcard
            }
        }
        int separator = Math.max(argument.lastIndexOf('/'), argument.lastIndexOf('\\'));
        String dir = separator < 0 ? "." : argument.substring(0, separator + 1);
        PathMatcher pattern = Globs.matcher(argument.substring(separator + 1));
        List<Path> matches = new ArrayList<>();
        FileWalker.builder().maxDepth(1).includeHidden(params.all()).order(BY_NAME).build()
                .walk(context.currentDirectory().resolve(dir).normalize(),
                        (path, _) -> pattern.matches(path.getFileName()) ? path : null, matches::add,
                        error -> report(error, context));
        if (matches.isEmpty()) {
            context.error(Messages.get("grep.no.match", argument));
        }
        return matches;
    }

    private static void report(WalkError error, CmdletContext<?> context) {
        context.error(switch (error.kind()) {
            case ACCESS_DENIED -> Messages.get("grep.access.denied", error.path());
            case NOT_FOUND -> Messages.get("grep.not.found", error.path());
            case IO -> Messages.get("grep.read.failed", error.path(), error.message());
        });
    }

    /** One execution of {@code grep}: compiled pattern and settings of the walk. */
    private static final class Search {

        private final Pattern pattern;
        private final boolean invert;
        private final Charset charset;
        /** Off with --binary, and for UTF-16 and UTF-32 texts, which contain NUL bytes. */
        private final boolean detectBinary;
        private final ScriptBlock property;
        private final FileWalker walker;

        Search(Params params, CmdletContext<?> context) {
            if (params.parallel() != null && params.parallel() < 1) {
                throw new IllegalArgumentException(Messages.get("grep.parallel.invalid", params.parallel()));
            }
            if (params.maxDepth() != null && params.maxDepth() < 1) {
                throw new IllegalArgumentException(Messages.get("grep.maxDepth.invalid", params.maxDepth()));
            }
            try {
                pattern = Pattern.compile(params.pattern(),
                        params.ignoreCase() ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException(Messages.get("grep.pattern.invalid", params.pattern(),
                        e.getDescription(), e.getIndex()));
            }
            invert = params.invert();
            charset = charset(params.encoding());
            detectBinary = !params.binary()
                    && !charset.name().startsWith("UTF-16") && !charset.name().startsWith("UTF-32");
            property = params.property() == null ? null : property(params.property(), context);
            int maxDepth = params.maxDepth() != null ? params.maxDepth()
                    : params.recurse() ? Integer.MAX_VALUE : 1;
            walker = FileWalker.builder().maxDepth(maxDepth).includeHidden(params.all()).order(BY_NAME)
                    .parallelism(params.parallel() == null ? 1 : params.parallel()).build();
        }

        private static Charset charset(String encoding) {
            if (encoding == null) {
                return StandardCharsets.UTF_8;
            }
            try {
                return Charset.forName(encoding);
            } catch (IllegalCharsetNameException | UnsupportedCharsetException _) {
                throw new IllegalArgumentException(Messages.get("grep.encoding.unknown", encoding));
            }
        }

        private static ScriptBlock property(String name, CmdletContext<?> context) {
            if (!Where.PROPERTY.matcher(name).matches()) {
                throw new IllegalArgumentException(Messages.get("where.property.invalid", name));
            }
            return context.compile("$_." + name);
        }

        /** Searches the files below the roots; the lines of a file are emitted together, in order. */
        void files(List<Path> roots, CmdletContext<Object> context) {
            walker.walk(roots, this::search, matches -> matches.forEach(context::emit),
                    error -> report(error, context));
        }

        /** Lets the object through if its text (or that of its property) matches. */
        void filter(Object input, CmdletContext<Object> context) {
            Object value = input;
            if (property != null) {
                try {
                    value = property.invoke(input);
                } catch (CancellationException e) {
                    throw e;
                } catch (RuntimeException e) {
                    context.error(Messages.get("pipeline.object.skipped", e.getMessage(), Where.abbreviate(input)));
                    return;
                }
            }
            String text = value == null ? "" : value.toString();
            if (pattern.matcher(text).find() != invert) {
                context.emit(input);
            }
        }

        /** Matching lines of a file; called in a virtual thread when the walk is parallel. */
        private List<GrepMatch> search(Path file, BasicFileAttributes attributes) throws IOException {
            if (!attributes.isRegularFile()) {
                return null;
            }
            Path absolute = file.toAbsolutePath();
            List<GrepMatch> matches = new ArrayList<>();
            try (InputStream in = Files.newInputStream(file)) {
                byte[] head = in.readNBytes(BINARY_PROBE);
                if (detectBinary && binary(head)) {
                    return null;
                }
                var reader = new BufferedReader(new InputStreamReader(
                        new SequenceInputStream(new ByteArrayInputStream(head), in), charset.newDecoder()));
                String text;
                int number = 0;
                while ((text = reader.readLine()) != null) {
                    number++;
                    if ((number & 0xFFF) == 0 && Thread.currentThread().isInterrupted()) {
                        throw new CancellationException();
                    }
                    GrepMatch match = match(absolute, number, text);
                    if (match != null) {
                        matches.add(match);
                    }
                }
            } catch (CharacterCodingException _) {
                // no fallback: the user chooses the charset with --encoding
                throw new IOException(Messages.get("grep.decode.failed", charset.name()));
            }
            return matches.isEmpty() ? null : matches;
        }

        private static boolean binary(byte[] head) {
            for (byte b : head) {
                if (b == 0) {
                    return true;
                }
            }
            return false;
        }

        /** Line as a {@link GrepMatch} if it is kept, otherwise {@code null}. */
        GrepMatch match(Path path, int number, String text) {
            Matcher matcher = pattern.matcher(text);
            if (!matcher.find()) {
                return invert ? new GrepMatch(path, number, text, List.of(), List.of(), Map.of()) : null;
            }
            if (invert) {
                return null;
            }
            List<String> groups = new ArrayList<>(matcher.groupCount() + 1);
            for (int i = 0; i <= matcher.groupCount(); i++) {
                groups.add(matcher.group(i));
            }
            Map<String, String> named = new LinkedHashMap<>();
            pattern.namedGroups().entrySet().stream().sorted(Map.Entry.comparingByValue())
                    .forEach(group -> named.put(group.getKey(), matcher.group(group.getValue())));
            List<Span> spans = new ArrayList<>();
            do {
                if (matcher.end() > matcher.start()) {
                    spans.add(new Span(matcher.start(), matcher.end()));
                }
            } while (matcher.find());
            return new GrepMatch(path, number, text, spans, Collections.unmodifiableList(groups),
                    Collections.unmodifiableMap(named));
        }
    }
}
