package io.powerj.cmdlets;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.ScriptBlock;
import io.powerj.fs.WalkError;

/**
 * {@code cat}: reads files, as lines or as a whole content (specification FR-65).
 * <pre>
 * cat a.txt b.txt                       # a String per line
 * cat a.txt b.txt -n                    # CatLine: line (numbering goes on in b.txt), length, text
 * cat *.txt -t                          # one String: the files one after the other
 * cat a.txt -c                          # one CatContent: length, content
 * ls --filter *.txt | cat               # files received from the pipeline
 * grep TODO -r -s | cat --property path # the file is a property of the objects received
 * </pre>
 */
@CmdletInfo(name = "cat", preview = true, category = "Files", summary = "Reads files as lines or as a whole content",
        examples = {"cat a.txt", "cat a.txt b.txt -n", "cat *.log -t", "cat pom.xml -c",
                "cat data.txt --encoding ISO-8859-1", "ls --filter *.txt | cat -n",
                "\"a.txt\" | cat", "grep TODO -r -s | cat --property path -n",
                "(ls -r --filter *.java | cat -n)[-1].line"})
public final class Cat implements Cmdlet<Cat.Params, Object, Object> {

    /** Output of {@code cat}, chosen by --lines, --text, --numbered or --content. */
    private enum Mode {
        /** A {@code String} per line. */
        LINES,
        /** One {@code String}: the whole content. */
        TEXT,
        /** A {@link CatLine} per line. */
        NUMBERED,
        /** One {@link CatContent}: the whole content. */
        CONTENT
    }

    /** Parameters of {@code cat}. */
    public record Params(
            @Option(position = 0, description = "Files to read (wildcards * and ? accepted); "
                    + "default: the pipeline objects")
            List<String> paths,
            @Option(shortName = 'l', description = "Output: a String per line (default)")
            boolean lines,
            @Option(shortName = 't', description = "Output: one String, the content of all the files")
            boolean text,
            @Option(shortName = 'n', description = "Output: line number, length and text of each line")
            boolean numbered,
            @Option(shortName = 'c', description = "Output: length and content of all the files, in one object")
            boolean content,
            @Option(description = "Charset of the files (default: UTF-8)")
            String encoding,
            @Option(description = "Property of the pipeline objects that gives the file (String, Path or File)")
            String property) {
    }

    /** State of each running execution: the same cmdlet can be used in several pipelines at once. */
    private final Map<Params, Reading> readings = Collections.synchronizedMap(new IdentityHashMap<>());

    @Override
    public void begin(Params params, CmdletContext<Object> context) {
        Reading reading = new Reading(params, context);
        readings.put(params, reading);
        if (!params.paths().isEmpty()) {
            for (String argument : params.paths()) {
                for (Path file : FileArguments.expand(argument, false, context, error -> report(error, context),
                        "cat.no.match")) {
                    reading.read(file, context);
                }
            }
        } else if (!context.hasInput()) {
            throw new IllegalArgumentException(Messages.get("cat.file.expected"));
        }
    }

    @Override
    public void process(Params params, Object input, CmdletContext<Object> context) {
        Reading reading = readings.computeIfAbsent(params, p -> new Reading(p, context));
        if (params.property() == null && input instanceof FileEntry entry && entry.dir()) {
            return; // a directory listed by ls is skipped: ls -r already emits its files
        }
        Path file = reading.file(input, context);
        if (file != null) {
            reading.read(file, context);
        }
    }

    @Override
    public void end(Params params, CmdletContext<Object> context) {
        Reading reading = readings.remove(params);
        if (reading != null) {
            reading.finish(context);
        }
    }

    private static void report(WalkError error, CmdletContext<?> context) {
        context.error(switch (error.kind()) {
            case ACCESS_DENIED -> Messages.get("cat.access.denied", error.path());
            case NOT_FOUND -> Messages.get("cat.not.found", error.path());
            case IO -> Messages.get("cat.read.failed", error.path(), error.message());
        });
    }

    /** One execution of {@code cat}: settings, line counter and content gathered. */
    private static final class Reading {

        private final Mode mode;
        private final Charset charset;
        private final ScriptBlock property;
        private final String propertyName;
        /** Number of the last line read: goes on from one file to the next. */
        private int line;
        /** Whole content, with --text or --content; {@code null} while no file is read. */
        private StringBuilder content;

        Reading(Params params, CmdletContext<?> context) {
            mode = mode(params);
            charset = FileArguments.charset(params.encoding(), "cat.encoding.unknown");
            propertyName = params.property();
            property = propertyName == null ? null : property(propertyName, context);
        }

        /** Output chosen by the options: one at most, --lines by default. */
        private static Mode mode(Params params) {
            int chosen = (params.lines() ? 1 : 0) + (params.text() ? 1 : 0) + (params.numbered() ? 1 : 0)
                    + (params.content() ? 1 : 0);
            if (chosen > 1) {
                throw new IllegalArgumentException(Messages.get("cat.one.output"));
            }
            return params.text() ? Mode.TEXT : params.numbered() ? Mode.NUMBERED
                    : params.content() ? Mode.CONTENT : Mode.LINES;
        }

        private static ScriptBlock property(String name, CmdletContext<?> context) {
            if (!Where.PROPERTY.matcher(name).matches()) {
                throw new IllegalArgumentException(Messages.get("where.property.invalid", name));
            }
            return context.compile("$_." + name);
        }

        /** File designated by a pipeline object, or {@code null} after reporting why there is none. */
        Path file(Object input, CmdletContext<?> context) {
            Object value = input;
            if (property != null) {
                try {
                    value = property.invoke(input);
                } catch (CancellationException e) {
                    throw e;
                } catch (RuntimeException e) {
                    context.error(Messages.get("pipeline.object.skipped", e.getMessage(), Where.abbreviate(input)));
                    return null;
                }
            }
            Path file;
            try {
                file = value instanceof String text ? context.currentDirectory().resolve(text)
                        : FileArguments.file(value, context);
            } catch (InvalidPathException e) {
                context.error(Messages.get("cat.read.failed", value, e.getMessage()));
                return null;
            }
            if (file == null) {
                context.error(property == null
                        ? Messages.get("cat.not.a.file", Where.abbreviate(input))
                        : Messages.get("cat.property.not.a.file", propertyName, Where.abbreviate(input)));
            }
            return file;
        }

        /** Reads a file: its lines are emitted at once, or its content is gathered for {@link #finish}. */
        void read(Path file, CmdletContext<Object> context) {
            Path absolute = file.toAbsolutePath().normalize();
            if (Files.isDirectory(absolute)) {
                context.error(Messages.get("cat.is.directory", absolute));
                return;
            }
            try (var reader = new BufferedReader(new InputStreamReader(Files.newInputStream(absolute),
                    charset.newDecoder()))) {
                if (mode == Mode.TEXT || mode == Mode.CONTENT) {
                    // gathered aside: a file that cannot be decoded adds nothing
                    var text = new StringBuilder();
                    char[] buffer = new char[8192];
                    int count;
                    while ((count = reader.read(buffer)) >= 0) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new CancellationException();
                        }
                        text.append(buffer, 0, count);
                    }
                    (content == null ? content = new StringBuilder() : content).append(text);
                    return;
                }
                String text;
                while ((text = reader.readLine()) != null) {
                    line++;
                    if ((line & 0xFFF) == 0 && Thread.currentThread().isInterrupted()) {
                        throw new CancellationException();
                    }
                    context.emit(mode == Mode.LINES ? text : new CatLine(absolute, line, text.length(), text));
                }
            } catch (CharacterCodingException _) {
                // no fallback: the user chooses the charset with --encoding
                context.error(Messages.get("cat.read.failed", absolute,
                        Messages.get("cat.decode.failed", charset.name())));
            } catch (NoSuchFileException _) {
                context.error(Messages.get("cat.not.found", absolute));
            } catch (AccessDeniedException _) {
                context.error(Messages.get("cat.access.denied", absolute));
            } catch (IOException e) {
                context.error(Messages.get("cat.read.failed", absolute, e.getMessage()));
            }
        }

        /** Emits the whole content, with --text or --content, if at least one file was read. */
        void finish(CmdletContext<Object> context) {
            if (content == null) {
                return;
            }
            String text = content.toString();
            context.emit(mode == Mode.TEXT ? text : new CatContent(text.length(), text));
        }
    }
}
