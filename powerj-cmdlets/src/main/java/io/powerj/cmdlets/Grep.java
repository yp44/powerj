package io.powerj.cmdlets;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.ScriptBlock;
import io.powerj.api.Span;
import io.powerj.api.UsageException;
import io.powerj.fs.FileWalker;
import io.powerj.text.GuardedText;

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
 * grep TODO -r -s | map { m -> m.path }  # files with at least one match, each read up to it
 * grep -s -v Copyright src -r            # files that never match (line 0)
 * </pre>
 */
@CmdletInfo(name = "grep", preview = true, category = "Files", summary = "Searches a regular expression in files or pipeline objects",
        examples = {"grep TODO src -r", "grep -i \"error|warn\" *.log", "grep \"class (\\\\w+)\" -r -p 16 | map { m -> m.groups[1] }", "grep class\\s+\\w+ src -r",
                "grep \"version=\\\"(?<v>[^\\\"]+)\\\"\" pom.xml | map { m -> m.named.v }",
                "ls -r --filter *.java | grep TODO", "git log --oneline | grep -i fix", "ls | grep --property name \"^a\"",
                "grep MAGIC data.bin --binary --encoding ISO-8859-1", "grep TODO -r -s | map { m -> m.path }",
                "grep -s -v Copyright src -r"})
public final class Grep implements Cmdlet<Grep.Params, Object, Object> {

    /** Parameters of {@code grep}. */
    public record Params(
            @Option(position = 0, mandatory = true, description = "Regular expression (java.util.regex syntax)")
            String pattern,
            @Option(position = 1, path = true, description = "Files or directories to search (wildcards * and ? accepted); "
                    + "default: current directory, or the pipeline objects")
            List<Object> paths,
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
            boolean binary,
            @Option(shortName = 's', description = "Only the first matching line of each file, which is not read "
                    + "further; with -v, the files that never match (line 0)")
            boolean singleMatch) {
    }

    /** Bytes read to recognize a binary file (one containing a NUL byte), skipped. */
    private static final int BINARY_PROBE = 8192;
    /** Matches of one file handed over together by a parallel walk: the lines of a file stay together up to this many. */
    private static final int PARALLEL_CHUNK = 1000;

    private final Opener opener;

    public Grep() {
        this(Files::newInputStream);
    }

    /** A {@code grep} with its own way to open a file (the tests count what is read). */
    Grep(Opener opener) {
        this.opener = opener;
    }

    private Search search(Params params, CmdletContext<Object> context) {
        return context.state(Search.class, () -> new Search(params, context, opener));
    }

    @Override
    public void begin(Params params, CmdletContext<Object> context) {
        Search search = search(params, context);
        if (!params.paths().isEmpty()) {
            List<Path> roots = new ArrayList<>();
            for (Object argument : params.paths()) {
                roots.addAll(FileArguments.expandOrReport(argument, params.all(), context, "grep.no.match"));
            }
            search.files(roots, context);
        } else if (!context.hasInput()) {
            search.files(List.of(context.currentDirectory()), context);
        }
    }

    @Override
    public void process(Params params, Object input, CmdletContext<Object> context) {
        Search search = search(params, context);
        Path file = params.property() == null ? FileArguments.file(input, context) : null;
        if (file == null) {
            search.filter(input, context);
        } else if (params.recurse() || params.maxDepth() != null || !Files.isDirectory(file)) {
            // a directory received without -r is skipped: ls -r already emits its files
            search.files(List.of(file), context);
        }
    }

    /** One execution of {@code grep}: compiled pattern and settings of the walk. */
    private static final class Search {

        private final Pattern pattern;
        private final boolean invert;
        private final Charset charset;
        /** Off with --binary, and for UTF-16 and UTF-32 texts, which contain NUL bytes. */
        private final boolean detectBinary;
        private final ScriptBlock property;
        /** --single-match: stop reading a file at its first match. */
        private final boolean singleMatch;
        private final FileWalker walker;
        private final Opener opener;
        /** Matches handed over together: one by one when the walk is sequential, so nothing waits for the end of the file. */
        private final int chunk;

        Search(Params params, CmdletContext<?> context, Opener opener) {
            this.opener = opener;
            int parallelism = FileOps.parallelism(params.parallel());
            if (params.maxDepth() != null && params.maxDepth() < 1) {
                throw new UsageException(Messages.get("files.maxDepth.invalid", params.maxDepth()));
            }
            try {
                pattern = Pattern.compile(params.pattern(),
                        params.ignoreCase() ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0);
            } catch (PatternSyntaxException e) {
                throw new UsageException(Messages.get("grep.pattern.invalid", params.pattern(),
                        e.getDescription(), e.getIndex()));
            }
            invert = params.invert();
            charset = FileArguments.charset(params.encoding(), "grep.encoding.unknown");
            detectBinary = !params.binary()
                    && !charset.name().startsWith("UTF-16") && !charset.name().startsWith("UTF-32");
            property = params.property() == null ? null : PropertyPath.of(params.property(), context);
            singleMatch = params.singleMatch();
            int maxDepth = params.maxDepth() != null ? params.maxDepth()
                    : params.recurse() ? Integer.MAX_VALUE : 1;
            chunk = parallelism == 1 ? 1 : PARALLEL_CHUNK;
            walker = FileWalker.builder().maxDepth(maxDepth).includeHidden(params.all()).order(FileArguments.BY_NAME)
                    .parallelism(parallelism).build();
        }

        /**
         * Searches the files below the roots. A sequential walk emits each match as the line is read, so that nothing
         * is kept and a consumer that stops ({@code | first 1}) stops the reading; a parallel one hands over the matches of
         * a file by chunks of {@link #PARALLEL_CHUNK}, in order.
         */
        void files(List<Path> roots, CmdletContext<Object> context) {
            List<Path> readable = new ArrayList<>();
            for (Path root : roots) {
                if (Files.exists(root) && !Files.isDirectory(root) && !Files.isRegularFile(root)) {
                    // a named device, FIFO or socket: refused as cat does (audit v2, S-04); inside a walk it is skipped
                    context.error(Messages.get("files.not.regular", root.toAbsolutePath()));
                } else {
                    readable.add(root);
                }
            }
            walker.walkStreaming(readable, this::search, matches -> matches.forEach(context::emit),
                    error -> FileArguments.report(error, context));
        }

        /** Lets the object through if its text (or that of its property) matches. */
        void filter(Object input, CmdletContext<Object> context) {
            Object value = input;
            if (property != null) {
                var attempt = property.tryInvoke(input);
                if (attempt.failed()) {
                    Skipped.report(context, attempt.error(), input);
                    return;
                }
                value = attempt.value();
            }
            String text = value == null ? "" : value.toString();
            if (pattern.matcher(GuardedText.interruptible(text)).find() != invert) {
                context.emit(input);
            }
        }

        /**
         * Gives the matching lines of a file to {@code sink}, a few at a time (see {@link #chunk}); called in a virtual
         * thread when the walk is parallel. What was found before a failure (bytes the charset cannot decode, a line that
         * is too long) is given before the failure is reported.
         */
        private void search(Path file, BasicFileAttributes attributes, Consumer<List<GrepMatch>> sink)
                throws IOException {
            if (!attributes.isRegularFile()) {
                return;
            }
            Path absolute = file.toAbsolutePath();
            List<GrepMatch> pending = new ArrayList<>();
            int number = 0;
            List<Integer> tooLong = new ArrayList<>(); // lines skipped for their length, as cat does
            try (InputStream in = opener.open(file)) {
                byte[] head = in.readNBytes(BINARY_PROBE);
                if (detectBinary && binary(head)) {
                    return;
                }
                var lines = new BoundedLines(new DecodingReader(
                        new SequenceInputStream(new ByteArrayInputStream(head), in), charset), BoundedLines.MAX_LINE);
                while (true) {
                    String text;
                    try {
                        text = lines.next();
                    } catch (BoundedLines.TooLong _) {
                        number++;
                        tooLong.add(number);
                        continue;
                    }
                    if (text == null) {
                        break;
                    }
                    number++;
                    if (singleMatch && invert) {
                        if (pattern.matcher(GuardedText.interruptible(text)).find()) {
                            return; // the file matches: excluded, not read further
                        }
                        continue;
                    }
                    GrepMatch match = match(absolute, number, text);
                    if (match != null) {
                        pending.add(match);
                        if (singleMatch) {
                            break; // first match: the rest of the file is not read
                        }
                        if (pending.size() >= chunk) {
                            sink.accept(List.copyOf(pending));
                            pending.clear();
                        }
                    }
                }
                if (singleMatch && invert) {
                    pending.add(new GrepMatch(absolute, 0, "", List.of(), List.of(), Map.of()));
                }
            } catch (CharacterCodingException _) {
                // no fallback: the user chooses the charset with --encoding. The matches of the lines before it are kept.
                flush(pending, sink);
                throw new IOException(Messages.get("grep.decode.failed", charset.name(), number + 1));
            }
            flush(pending, sink);
            if (!tooLong.isEmpty()) { // the file was searched but for those lines: say so, after its matches
                throw new IOException(Messages.get("grep.line.too.long", tooLong.getFirst(), tooLong.size(),
                        BoundedLines.MAX_LINE));
            }
        }

        private static void flush(List<GrepMatch> pending, Consumer<List<GrepMatch>> sink) {
            if (!pending.isEmpty()) {
                sink.accept(List.copyOf(pending));
                pending.clear();
            }
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
            Matcher matcher = pattern.matcher(GuardedText.interruptible(text));
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
