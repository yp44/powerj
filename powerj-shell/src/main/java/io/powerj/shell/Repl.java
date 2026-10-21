package io.powerj.shell;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;
import org.jline.utils.InfoCmp;

import io.powerj.core.BuildInfo;
import io.powerj.core.exec.CmdletRegistry;
import io.powerj.core.exec.Completions;
import io.powerj.core.exec.ConsoleInput;
import io.powerj.core.exec.Interpreter;
import io.powerj.core.exec.Outcome;
import io.powerj.core.exec.PerfFiles;
import io.powerj.core.exec.PerfProfile;
import io.powerj.core.exec.PerfProfiler;
import io.powerj.core.exec.PerfRecorder;
import io.powerj.core.exec.PerfReport;
import io.powerj.core.exec.PjException;
import io.powerj.core.exec.Session;
import io.powerj.core.exec.ShellIo;
import io.powerj.core.exec.Supervisor;
import io.powerj.core.exec.Values;

/**
 * Read-execute loop of the shell: reads a line with JLine (history, Ctrl+R…), executes it
 * under {@link Supervisor} and displays the result. Never lets an exception escape.
 */
public final class Repl {

    private final LineReader reader;
    private final Supervisor supervisor;
    private final Session session;
    private final Interpreter interpreter;
    private final Terminal terminal;
    private final PrintWriter out;
    /** Preview mode (FR-66), or {@code null} if the line reader cannot display it. */
    private final Previewer previewer;
    /** Perf mode (FR-76), or {@code null} if the line reader cannot display it. */
    private final PerfMode perf;
    private final boolean colors;
    /** What the Flight Recorder saw during the last line run in perf mode (FR-78), for the {@code perf} command. */
    private volatile PerfProfile lastProfile;
    private List<String> moduleWarnings = List.of();
    private boolean flightNoted;
    private Path profile;
    private boolean runProfile;
    /** Prefix of the error lines ({@code profile.pj:3: }) while the profile runs, otherwise {@code null}. */
    private volatile String errorPrefix;

    public Repl(LineReader reader, Supervisor supervisor, Session session, CmdletRegistry registry) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
        this.session = Objects.requireNonNull(session, "session");
        this.terminal = reader.getTerminal();
        this.out = terminal.writer();
        boolean interactive = !terminal.getType().startsWith("dumb");
        this.colors = interactive && colors(terminal);
        var io = new ShellIo(out, this::printError, interactive, () -> terminal.getWidth(), consoleInput(terminal),
                colors);
        this.interpreter = new Interpreter(session, io, Map.of("history", this::history, "perf", this::perfCommand), registry);
        // Completion (Tab) and input highlighting (FR-08, FR-21 to FR-26).
        var completions = new Completions(interpreter);
        completions.warmUp();
        Previewer preview = null;
        PerfMode perfMode = null;
        if (reader instanceof LineReaderImpl impl) {
            impl.setParser(new ShellParser(completions));
            impl.setCompleter(ShellCompletion.completer());
            impl.setHighlighter(ShellCompletion.highlighter(interpreter));
        }
        if (reader instanceof PreviewLineReader previewReader && interactive) {
            preview = new Previewer(previewReader, interpreter, completions, terminal::getWidth, colors);
            preview.bind(this::prompt);
            perfMode = new PerfMode(previewReader, interpreter, terminal::getWidth);
            perfMode.bind(this::prompt);
            preview.onEnable(perfMode::off);
            perfMode.onEnable(preview::off);
        }
        this.previewer = preview;
        this.perf = perfMode;
        // Ctrl+C during execution cancels the command without exiting the shell (FR-03);
        // during input, JLine throws UserInterruptException.
        terminal.handle(Terminal.Signal.INT, _ -> supervisor.cancel());
    }

    /** {@code true} if the terminal displays ANSI colors (FR-64), unless {@code NO_COLOR} is set. */
    private static boolean colors(Terminal terminal) {
        Integer colors = terminal.getNumericCapability(InfoCmp.Capability.max_colors);
        String noColor = System.getenv("NO_COLOR");
        return colors != null && colors >= 8 && (noColor == null || noColor.isEmpty());
    }

    /** Loads third-party modules (§4.4); warnings are displayed below the banner. */
    public void loadModules(java.nio.file.Path dir) {
        moduleWarnings = interpreter.loadModules(dir);
    }

    /**
     * Startup profile (FR-63): {@code $profile} designates {@code file}, which is run before the first prompt
     * if {@code run} ({@code false} with {@code --no-profile}).
     */
    public void useProfile(Path file, boolean run) {
        this.profile = file;
        this.runProfile = run;
    }

    /**
     * Runs the loop until {@code exit} or Ctrl+D on an empty line.
     *
     * @return the shell's exit code
     */
    public int run(BuildInfo buildInfo) {
        out.println(buildInfo.banner());
        moduleWarnings.forEach(this::printError);
        out.flush();
        runProfile();
        while (session.exitRequest().isEmpty()) {
            String line;
            if (terminal.paused()) {
                terminal.resume(); // a command abandoned with a second Ctrl+C may still hold the console
            }
            session.jobs().drainFinished().forEach(out::println); // FR-75: background jobs ended meanwhile
            out.flush();
            try {
                line = reader.readLine(prompt());
            } catch (UserInterruptException _) {
                continue; // Ctrl+C during input: the line is cleared
            } catch (EndOfFileException _) {
                return 0; // Ctrl+D on an empty line, or end of input
            } finally {
                if (previewer != null) {
                    previewer.reset(); // stops the evaluation of the line being previewed (FR-66)
                }
                if (perf != null) {
                    perf.reset();
                }
            }
            PerfRecorder recorder = perf == null || line.isBlank() ? null : perf.recorder();
            PerfProfiler.Recording flight = recorder == null ? null : startFlightRecorder();
            interpreter.record(recorder);
            Outcome outcome;
            try {
                outcome = supervisor.run(line, () -> execute(line));
            } finally {
                interpreter.record(null);
            }
            switch (outcome) {
                case Outcome.Success _ -> { }
                case Outcome.Failure(var error) -> printError(error.message());
                case Outcome.Cancelled() -> printError("^C");
                case Outcome.Abandoned(var commandLine) ->
                        printError(Messages.get("command.abandoned", commandLine));
            }
            if (recorder != null) {
                printPerf(recorder.report());
                printProfile(flight);
            }
            out.flush();
        }
        return session.exitRequest().getAsInt();
    }

    /**
     * Runs the profile's commands like typed lines; their errors are prefixed with the file and the line, and
     * do not stop the following commands. Ctrl+C stops the whole profile.
     */
    private void runProfile() {
        if (profile == null) {
            return;
        }
        session.setVariable("profile", profile);
        if (!runProfile) {
            return;
        }
        List<Profile.Command> commands;
        try {
            commands = Profile.read(profile);
        } catch (IOException e) {
            printError(Messages.get("profile.readFailed", profile, e.getMessage()));
            return;
        }
        String name = profile.getFileName().toString();
        try {
            for (var command : commands) {
                errorPrefix = name + ":" + command.line() + ": ";
                var outcome = supervisor.run(command.text(), () -> execute(command.text()));
                switch (outcome) {
                    case Outcome.Success _ -> { }
                    case Outcome.Failure(var error) -> printError(error.message());
                    case Outcome.Cancelled() -> {
                        printError(Messages.get("profile.cancelled"));
                        return;
                    }
                    case Outcome.Abandoned(var commandLine) -> {
                        printError(Messages.get("command.abandoned", commandLine));
                        return;
                    }
                }
                out.flush();
                if (session.exitRequest().isPresent()) {
                    return;
                }
            }
        } finally {
            errorPrefix = null;
            out.flush();
        }
    }

    /**
     * Suspends JLine's reading of the console while a native program reads it (vim…): under Windows its
     * pump thread reads the console permanently and would steal keys, and turn Ctrl+C into a cancellation.
     */
    private static ConsoleInput consoleInput(Terminal terminal) {
        if (!terminal.canPauseResume()) {
            return ConsoleInput.NONE;
        }
        return new ConsoleInput() {
            @Override
            public void pause() {
                // Never pause(true): it interrupts the pump thread, and an interrupt landing while the pump
                // hands over an input event makes JLine close the whole terminal. pause() only asks the
                // pump to stop at its next poll (at most 100 ms, during the program's start-up).
                terminal.pause();
            }

            @Override
            public void resume() {
                terminal.resume();
            }
        };
    }

    /** Starts the Flight Recorder for the line about to run; {@code null} (and a note) if it cannot. */
    private PerfProfiler.Recording startFlightRecorder() {
        try {
            PerfProfiler.Recording recording = PerfProfiler.start(PerfProfiler.period(session.environment()));
            if (recording == null && !flightNoted) {
                flightNoted = true;
                printNote(Messages.get("perf.profile.noJfr"));
            }
            return recording;
        } catch (RuntimeException | LinkageError e) {
            printNote(Messages.get("perf.profile.unavailable", e));
            return null;
        }
    }

    /** Stops the recording, analyses it, and shows the summary under the report; never fails the line. */
    private void printProfile(PerfProfiler.Recording flight) {
        if (flight == null) {
            return;
        }
        try {
            Path dir = session.configDirectory().orElse(Path.of(System.getProperty("java.io.tmpdir"), "powerj"))
                    .resolve("perf");
            PerfProfile profile = flight.finish(dir, PerfFiles.keep(session.environment()));
            lastProfile = profile;
            synchronized (out) {
                perf.profile(profile).forEach(line -> out.println(colors ? line.toAnsi(terminal) : line.toString()));
                out.flush();
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            printNote(Messages.get("perf.profile.unavailable", e));
        } finally {
            flight.close();
        }
    }

    private void printNote(String message) {
        synchronized (out) {
            out.println(new AttributedString(message, AttributedStyle.DEFAULT.faint()).toAnsi(terminal));
            out.flush();
        }
    }

    /**
     * {@code perf}: what the Flight Recorder saw during the last line run in perf mode, as objects — hot methods
     * by default, {@code --stages}, {@code --allocations}, {@code --waits}, {@code --gc}, {@code --file}.
     */
    private List<Object> perfCommand(List<Object> args, Session ignored) {
        PerfProfile profile = lastProfile;
        if (profile == null) {
            throw new PjException(Messages.get("perf.none"));
        }
        String option = args.isEmpty() ? "" : Values.text(args.getFirst());
        if (args.size() > 1) {
            throw new PjException(Messages.get("perf.unknownOption", Values.text(args.get(1))));
        }
        List<Object> rows = new ArrayList<>();
        switch (option) {
            case "" -> profile.stages().forEach(stage -> rows.addAll(stage.hot()));
            case "--stages" -> profile.stages().forEach(stage -> rows.add(new PerfProfile.StageRow(stage.number(),
                    stage.command(), stage.kind(), stage.wall(), stage.cpu(), stage.samples(), stage.allocated(),
                    stage.waited())));
            case "--allocations" -> profile.stages().forEach(stage -> rows.addAll(stage.sites()));
            case "--waits" -> {
                profile.stages().forEach(stage -> rows.addAll(stage.waits()));
                rows.addAll(profile.otherWaits());
            }
            case "--gc" -> rows.add(new PerfProfile.GcRow(profile.gc().count(), profile.gc().pause(),
                    profile.gc().before(), profile.gc().after()));
            case "--file" -> {
                if (profile.file() == null) {
                    throw new PjException(Messages.get("perf.noFile", PerfFiles.VARIABLE));
                }
                rows.add(profile.file());
            }
            default -> throw new PjException(Messages.get("perf.unknownOption", option));
        }
        return rows;
    }

    /** Figures of the line that has just run (perf mode, FR-76). */
    private void printPerf(PerfReport report) {
        synchronized (out) {
            perf.report(report).forEach(line -> out.println(colors ? line.toAnsi(terminal) : line.toString()));
            out.flush();
        }
    }

    /** {@code PJ <dir>> }, {@code PJ? <dir>> } in preview mode (FR-66), {@code PJ⏱ <dir>> } in perf mode (FR-76). */
    String prompt() {
        String mode = "PJ ";
        if (perf != null && perf.on()) {
            mode = colors ? new AttributedString("PJ⏱", AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW))
                    .toAnsi(terminal) + " " : "PJ⏱ ";
        } else if (previewer != null && previewer.on()) {
            mode = colors ? new AttributedString("PJ?", AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN))
                    .toAnsi(terminal) + " " : "PJ? ";
        }
        return mode + session.currentDirectory() + "> ";
    }

    private List<Object> execute(String line) throws InterruptedException {
        var trimmed = line.strip();
        // A successful history expansion never leaves a "!" at the start of the line (FR-11).
        if (trimmed.startsWith("!") && trimmed.length() > 1 && !Character.isWhitespace(trimmed.charAt(1))) {
            throw new PjException(Messages.get("history.noMatch", trimmed.split("\\s+")[0]));
        }
        interpreter.execute(line);
        return List.of();
    }

    /** {@code history} (numbered list, numbers reusable with {@code !n}) or {@code history --clear}. */
    private List<Object> history(List<Object> args, Session ignored) throws Exception {
        if (args.equals(List.of("--clear"))) {
            reader.getHistory().purge();
            return List.of();
        }
        if (!args.isEmpty()) {
            throw new PjException(Messages.get("history.unknownOption", Values.text(args.getLast())));
        }
        List<Object> lines = new ArrayList<>();
        for (var entry : reader.getHistory()) {
            lines.add("%5d  %s".formatted(entry.index() + 1, entry.line()));
        }
        return lines;
    }

    /** Line of the PowerJ error stream, in red on a terminal that supports colors. */
    private void printError(String message) {
        String prefix = errorPrefix;
        if (prefix != null) {
            message = prefix + message;
        }
        synchronized (out) {
            out.println(new AttributedString(message, AttributedStyle.DEFAULT.foreground(AttributedStyle.RED))
                    .toAnsi(terminal));
            out.flush();
        }
    }
}
