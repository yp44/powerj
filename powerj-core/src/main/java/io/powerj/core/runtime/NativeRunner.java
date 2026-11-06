package io.powerj.core.runtime;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import io.powerj.core.error.PjError;
import io.powerj.core.error.PjException;
import io.powerj.core.format.OutputFormatter;
import io.powerj.core.i18n.Messages;
import io.powerj.core.nativeexec.BackgroundLauncher;
import io.powerj.core.nativeexec.ExecutableKind;
import io.powerj.core.nativeexec.NativeEncoding;
import io.powerj.core.nativeexec.NativeRun;
import io.powerj.fs.Host;

/**
 * Runs a native command (specification §3.10). Streams remain streams: stdout and stderr are
 * never wrapped in an object. The arguments are passed as they are once variables, {@code ~} and, on Linux and macOS,
 * the wildcards of the unquoted words are expanded ({@link NativeArguments}, FR-40); the quoting of the command line is
 * left to {@link ProcessBuilder}.
 * <ul>
 *   <li>display on a real terminal: inherited stdin/stdout/stderr (colors, {@code vim}, {@code ssh});</li>
 *   <li>capture ({@code $x = cmd}): stdout read as {@code String} lines, stderr to the error stream;</li>
 *   <li>redirections {@code >}, {@code >>}, {@code 2>}, {@code 2>>}: raw bytes to the file;</li>
 *   <li>Windows graphical application: launched detached (FR-39);</li>
 *   <li>{@code &}: launched in the background (FR-75).</li>
 * </ul>
 * Ctrl+C (thread interruption) stops the process and all its descendants (FR-57).
 */
public final class NativeRunner {

    private static final long GRACE_SECONDS = 2;

    /** Standard input of a background command: nothing to read. */
    private static final File NULL_DEVICE = new File(Host.windows() ? "NUL" : "/dev/null");

    /** Target file of a redirection. */
    public record FileTarget(Path file, boolean append) {
        ProcessBuilder.Redirect redirect() {
            return append ? ProcessBuilder.Redirect.appendTo(file.toFile()) : ProcessBuilder.Redirect.to(file.toFile());
        }
    }

    /** Result: metadata, and stdout lines if they were captured. */
    public record Result(NativeRun run, List<String> capturedLines) { }

    private final NativeEncoding encoding;
    /** Can the system start a background job in a process group of its own? Asked at the first background command. */
    private final BooleanSupplier groupLauncher;

    public NativeRunner() {
        this(new NativeEncoding());
    }

    NativeRunner(NativeEncoding encoding) {
        this(encoding, BackgroundLauncher::available);
    }

    /** @param groupLauncher {@code () -> false} stands for a system that cannot, to test what the runner does then */
    NativeRunner(NativeEncoding encoding, BooleanSupplier groupLauncher) {
        this.encoding = encoding;
        this.groupLauncher = groupLauncher;
    }

    /** Command of a native group (consecutive native stages of a pipeline). */
    public record Command(Path executable, List<String> args, boolean errorsToOutput) {
        public Command {
            args = List.copyOf(args);
        }

        /** {@code xeyes -geometry 100x100}: program name and arguments, on one line, for {@code jobs} and the perf summary. */
        public String text() {
            List<String> words = new ArrayList<>(args.size() + 1);
            words.add(executable.getFileName().toString());
            args.forEach(arg -> words.add(arg.isEmpty() || arg.chars().anyMatch(Character::isWhitespace)
                    ? '"' + arg.replace("\n", "\\n").replace("\t", "\\t") + '"' : arg));
            return String.join(" ", words);
        }
    }

    /**
     * Runs one native command alone in its pipeline.
     *
     * @param command the program, its arguments, and {@code 2>&1} (stderr joins stdout)
     * @param context the surroundings: {@code capture} reads the output as lines, the redirections give the files of
     *                {@code >} and {@code 2>}, {@code inheritInput} lets the process read the shell's standard input
     */
    public Result run(Command command, ExecutionContext context) throws InterruptedException {
        Path executable = command.executable();
        Session session = context.session();
        ShellIo io = context.io();
        boolean capture = context.capture();
        Optional<FileTarget> stdout = context.redirections().out();
        Optional<FileTarget> stderr = context.redirections().err();
        boolean errorsToOutput = command.errorsToOutput();
        boolean inheritInput = context.inheritInput();
        var builder = newBuilder(command, session);
        Charset charset = encoding.forProgram(executable, session.environment());

        boolean detached = !capture && stdout.isEmpty() && stderr.isEmpty()
                && Host.windows() && ExecutableKind.isWindowsGui(executable);
        boolean inheritOut = io.interactive() && !capture && stdout.isEmpty() && !detached;
        boolean inheritErr = io.interactive() && !capture && stderr.isEmpty() && !detached;

        builder.redirectInput(inheritInput && !detached
                ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.PIPE);
        builder.redirectErrorStream(errorsToOutput && !detached);
        builder.redirectOutput(detached ? ProcessBuilder.Redirect.DISCARD
                : stdout.map(FileTarget::redirect).orElse(inheritOut
                        ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.PIPE));
        builder.redirectError(detached ? ProcessBuilder.Redirect.DISCARD
                : stderr.map(FileTarget::redirect).orElse(inheritErr
                        ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.PIPE));

        // The program reads the console itself: the line editor must not read it at the same time.
        boolean console = io.interactive() && builder.redirectInput() == ProcessBuilder.Redirect.INHERIT;
        if (console) {
            io.console().pause();
        }
        try {
            return start(builder, command, context, detached, charset);
        } finally {
            if (console) {
                io.console().resume();
            }
        }
    }

    private Result start(ProcessBuilder builder, Command command, ExecutionContext context, boolean detached,
                         Charset charset) throws InterruptedException {
        Path executable = command.executable();
        List<String> args = command.args();
        ShellIo io = context.io();
        boolean capture = context.capture();
        long start = System.nanoTime();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new PjException(PjError.of(Messages.get("native.startFailed", executable, e.getMessage()), e));
        }
        if (builder.redirectInput() == ProcessBuilder.Redirect.PIPE) {
            closeQuietly(process.getOutputStream());
        }
        if (detached) {
            var run = new NativeRun(executable, args, process.pid(), null, elapsed(start));
            return new Result(run, List.of());
        }

        List<String> captured = Collections.synchronizedList(new ArrayList<>());
        List<Thread> pumps = new ArrayList<>(2);
        if (builder.redirectOutput() == ProcessBuilder.Redirect.PIPE) {
            Consumer<String> sink = capture ? captured::add : line -> {
                io.out().println(line);
                io.out().flush();
            };
            pumps.add(pump(process.getInputStream(), charset, sink));
        }
        if (builder.redirectError() == ProcessBuilder.Redirect.PIPE && !builder.redirectErrorStream()) {
            pumps.add(pump(process.getErrorStream(), charset, io.errors()));
        }

        int exitCode;
        try {
            exitCode = process.waitFor();
            for (Thread pump : pumps) {
                pump.join();
            }
        } catch (InterruptedException e) {
            destroyTree(process);
            throw e;
        }
        var run = new NativeRun(executable, args, process.pid(), exitCode, elapsed(start));
        return new Result(run, List.copyOf(captured));
    }

    /**
     * Runs native commands connected to one another (FR-37): bytes pass directly from one process
     * to the next, without decoding.
     *
     * @param context the surroundings; {@code inheritInput}: without {@code input}, the first process reads the shell's
     *                standard input; {@code sink}: where the lines of the last process go when the group ends the
     *                pipeline (or the terminal / file); {@code errors}: where the error lines go when they are not
     *                inherited; the file of {@code 2>} is in append mode, shared by all stages
     * @param input   objects to write to the first process's stdin (displayed form), or {@code null}
     * @param output  next stage, which receives the lines of the last process; {@code null} if the group ends the
     *                pipeline
     * @return metadata of each process, in order
     */
    public List<NativeRun> runGroup(List<Command> commands, ExecutionContext context, Source input, Pipe output)
            throws InterruptedException {
        Session session = context.session();
        ShellIo io = context.io();
        boolean inheritInput = context.inheritInput();
        Consumer<Object> sink = context.sink();
        boolean capture = context.capture();
        Optional<FileTarget> stdout = context.redirections().out();
        Optional<FileTarget> stderrFile = context.redirections().err();
        Consumer<String> errors = context.errors();
        List<ProcessBuilder> builders = new ArrayList<>(commands.size());
        for (int i = 0; i < commands.size(); i++) {
            Command command = commands.get(i);
            var builder = newBuilder(command, session);
            if (i == 0) {
                builder.redirectInput(input == null && inheritInput
                        ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.PIPE);
            }
            if (i == commands.size() - 1) {
                builder.redirectOutput(output == null && stdout.isPresent() ? stdout.get().redirect()
                        : output == null && io.interactive() && !capture
                                ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.PIPE);
            }
            if (command.errorsToOutput()) {
                builder.redirectErrorStream(true);
            } else if (stderrFile.isPresent()) {
                builder.redirectError(ProcessBuilder.Redirect.appendTo(stderrFile.get().file().toFile()));
            } else if (io.interactive()) {
                builder.redirectError(ProcessBuilder.Redirect.INHERIT);
            }
            builders.add(builder);
        }

        boolean console = io.interactive() && builders.getFirst().redirectInput() == ProcessBuilder.Redirect.INHERIT;
        if (console) {
            io.console().pause();
        }
        try {
            return startGroup(builders, commands, context, input, output);
        } finally {
            if (console) {
                io.console().resume();
            }
        }
    }

    private List<NativeRun> startGroup(List<ProcessBuilder> builders, List<Command> commands, ExecutionContext context,
                                       Source input, Pipe output) throws InterruptedException {
        Session session = context.session();
        ShellIo io = context.io();
        Consumer<Object> sink = context.sink();
        Consumer<String> errors = context.errors();
        long start = System.nanoTime();
        List<Process> processes;
        try {
            processes = ProcessBuilder.startPipeline(builders);
        } catch (IOException e) {
            throw startFailed(commands, e);
        }
        Runnable stop = () -> processes.forEach(NativeRunner::destroyTree);
        if (output != null) {
            output.onAbort(stop);
        }

        List<Thread> threads = new ArrayList<>();
        Thread writer = null;
        Process first = processes.getFirst();
        if (input != null) {
            Charset charset = encoding.forProgram(commands.getFirst().executable(), session.environment());
            writer = Thread.ofVirtual().name("powerj-stdin").start(() -> writeObjects(input, first, charset, io));
        } else if (builders.getFirst().redirectInput() == ProcessBuilder.Redirect.PIPE) {
            closeQuietly(first.getOutputStream());
        }
        Process last = processes.getLast();
        if (builders.getLast().redirectOutput() == ProcessBuilder.Redirect.PIPE) {
            Charset charset = encoding.forProgram(commands.getLast().executable(), session.environment());
            Consumer<String> lines = output != null ? output::put : sink::accept;
            threads.add(pump(last.getInputStream(), charset, lines));
        }
        for (int i = 0; i < processes.size(); i++) {
            if (builders.get(i).redirectError() == ProcessBuilder.Redirect.PIPE && !builders.get(i).redirectErrorStream()) {
                Charset charset = encoding.forProgram(commands.get(i).executable(), session.environment());
                threads.add(pump(processes.get(i).getErrorStream(), charset, errors));
            }
        }

        List<NativeRun> runs = new ArrayList<>(processes.size());
        try {
            for (int i = 0; i < processes.size(); i++) {
                int exitCode = processes.get(i).waitFor();
                Command command = commands.get(i);
                runs.add(new NativeRun(command.executable(), command.args(), processes.get(i).pid(), exitCode,
                        elapsed(start)));
            }
            if (writer != null) {
                // The first process no longer reads: the stage feeding it must stop.
                input.abort();
                writer.interrupt();
                writer.join();
            }
            for (Thread thread : threads) {
                thread.join();
            }
        } catch (InterruptedException e) {
            stop.run();
            if (writer != null) {
                input.abort();
                writer.interrupt();
            }
            throw e;
        }
        return runs;
    }

    /**
     * Starts native commands connected to one another in the background (FR-75), without waiting for them. Their
     * standard input is the null device: they never read the console. Their output goes to the terminal (inherited)
     * or to the file of a redirection; without a terminal, it is discarded.
     * <p>
     * Ctrl+C must stop the foreground command only, and the terminal sends it to the whole foreground process group. So the
     * job is started by {@link BackgroundLauncher} in a process group of its own, through the FFM API (issue #128): no
     * {@code sh} in between and the pid is the program's. When that cannot be done:
     * <ul>
     *   <li>on Windows, for a batch file (or on a system without the functions), by {@link ProcessBuilder}, which gives no
     *       protection against the Ctrl+C of the console;</li>
     *   <li>on Unix, on a system without {@code posix_spawn} as needed (glibc older than 2.34, musl), through {@code sh},
     *       which ignores {@code SIGINT} and {@code SIGQUIT} and replaces itself with the program ({@code exec}), <b>only</b>
     *       if the session variable {@value BackgroundLauncher#SH_FALLBACK_VARIABLE} is {@code true}; otherwise the
     *       command is refused with a message that names the variable.</li>
     * </ul>
     *
     * @param context the surroundings; the file of {@code 2>} is in append mode, shared by all stages
     * @return the processes, in order
     */
    public List<Process> startBackground(List<Command> commands, ExecutionContext context) {
        Session session = context.session();
        ShellIo io = context.io();
        Optional<FileTarget> stdout = context.redirections().out();
        Optional<FileTarget> stderrFile = context.redirections().err();
        List<BackgroundLauncher.Stage> stages = commands.stream()
                .map(c -> new BackgroundLauncher.Stage(c.executable(), c.args(), c.errorsToOutput())).toList();
        if (groupLauncher.getAsBoolean() && BackgroundLauncher.handles(stages)) {
            return startInGroup(stages, commands, session, io, stdout, stderrFile);
        }
        boolean throughSh = !Host.windows();
        if (throughSh && !BackgroundLauncher.shFallbackAllowed(session.environmentSnapshot())) {
            throw new PjException(Messages.get("background.unavailable", BackgroundLauncher.SH_FALLBACK_VARIABLE));
        }
        List<ProcessBuilder> builders = new ArrayList<>(commands.size());
        for (int i = 0; i < commands.size(); i++) {
            Command command = commands.get(i);
            var builder = newBuilder(throughSh ? shielded(command) : command, session);
            if (i == 0) {
                builder.redirectInput(ProcessBuilder.Redirect.from(NULL_DEVICE));
            }
            if (i == commands.size() - 1) {
                builder.redirectOutput(stdout.map(FileTarget::redirect).orElse(io.interactive()
                        ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.DISCARD));
            }
            if (command.errorsToOutput()) {
                builder.redirectErrorStream(true);
            } else {
                builder.redirectError(stderrFile.map(f -> ProcessBuilder.Redirect.appendTo(f.file().toFile()))
                        .orElse(io.interactive() ? ProcessBuilder.Redirect.INHERIT : ProcessBuilder.Redirect.DISCARD));
            }
            builders.add(builder);
        }
        try {
            return ProcessBuilder.startPipeline(builders);
        } catch (IOException e) {
            throw startFailed(commands, e);
        }
    }

    /**
     * The same job as the {@link ProcessBuilder} branch of {@link #startBackground} (null device in, then the file of the
     * redirection, else the terminal when there is one, else nothing; {@code 2>&1}), started in a process group of its own.
     * The environment is the snapshot of the session, never the JVM's.
     */
    private static List<Process> startInGroup(List<BackgroundLauncher.Stage> stages, List<Command> commands,
                                              Session session, ShellIo io, Optional<FileTarget> stdout,
                                              Optional<FileTarget> stderrFile) {
        BackgroundLauncher.Output terminal = io.interactive() ? BackgroundLauncher.Output.inherit()
                : BackgroundLauncher.Output.discard();
        BackgroundLauncher.Output out = stdout.map(f -> BackgroundLauncher.Output.file(f.file(), f.append()))
                .orElse(terminal);
        BackgroundLauncher.Output err = stderrFile.map(f -> BackgroundLauncher.Output.file(f.file(), true))
                .orElse(terminal);
        try {
            return BackgroundLauncher.start(stages, session.currentDirectory(), session.environmentSnapshot(), out, err);
        } catch (IOException e) {
            throw startFailed(commands, e);
        }
    }

    /**
     * The one place where a child process is set up: the command line, the directory of the session and its
     * environment. <b>A child process never inherits the environment of PowerJ</b>: the builder starts from the
     * environment of the JVM, which is cleared and replaced by the snapshot of the session. The redirections differ
     * from one way of starting to another, on purpose, and stay where they are.
     */
    private static ProcessBuilder newBuilder(Command command, Session session) {
        List<String> line = new ArrayList<>(command.args().size() + 1);
        line.add(command.executable().toString());
        line.addAll(command.args());
        var builder = new ProcessBuilder(line).directory(session.currentDirectory().toFile());
        builder.environment().clear();
        builder.environment().putAll(session.environmentSnapshot());
        return builder;
    }

    /**
     * The command run by {@code sh}, which ignores {@code SIGINT} and {@code SIGQUIT} and replaces itself with the program
     * ({@code exec}): the command line of a background process on a Unix system that cannot start it in a process group of
     * its own, when the session allows the fallback ({@value BackgroundLauncher#SH_FALLBACK_VARIABLE}).
     */
    private static Command shielded(Command command) {
        List<String> args = new ArrayList<>(command.args().size() + 4);
        args.addAll(List.of("-c", "trap '' INT QUIT; exec \"$0\" \"$@\"", command.executable().toString()));
        args.addAll(command.args());
        return new Command(Path.of("/bin/sh"), args, command.errorsToOutput());
    }

    /** The error of a group of processes that could not be started: the names of the programs, then the cause. */
    private static PjException startFailed(List<Command> commands, IOException e) {
        return new PjException(PjError.of(Messages.get("native.startFailed", commands.stream()
                .map(c -> c.executable().getFileName().toString()).toList(), e.getMessage()), e));
    }

    /** Writes the objects received to the process's stdin, in their displayed form (FR-37). */
    private static void writeObjects(Source input, Process process, Charset charset, ShellIo io) {
        var writer = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), charset)));
        var formatter = new OutputFormatter(writer, io.width().getAsInt());
        boolean complete = false;
        try {
            Object value;
            while ((value = input.next()) != Source.END) {
                formatter.accept(value);
                if (writer.checkError()) {
                    return; // the process closed its input
                }
            }
            complete = true;
        } catch (InterruptedException | CancellationException _) {
            // pipeline stopped
        } finally {
            if (complete) {
                formatter.close();
            }
            writer.close();
            if (!complete) {
                input.abort();
            }
        }
    }

    private static Thread pump(InputStream stream, Charset charset, Consumer<String> sink) {
        return Thread.ofVirtual().name("powerj-pump").start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(stream, charset))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sink.accept(line);
                }
            } catch (IOException | CancellationException _) {
                // process stopped, or next stage closed: end of reading
            }
        });
    }

    /** Stops the process and its descendants, forcibly if they do not terminate in time. */
    private static void destroyTree(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(GRACE_SECONDS, TimeUnit.SECONDS)) {
                descendants.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException _) {
            descendants.forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    private static void closeQuietly(OutputStream stream) {
        try {
            stream.close();
        } catch (IOException _) {
            // nothing to do
        }
    }
}
