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
    /** Perf mode (FR-76), or {@code null} without a terminal. */
    private final Perfer perfer;
    private final boolean colors;
    private List<String> moduleWarnings = List.of();
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
        this.interpreter = new Interpreter(session, io, Map.of("history", this::history), registry);
        // Completion (Tab) and input highlighting (FR-08, FR-21 to FR-26).
        var completions = new Completions(interpreter);
        completions.warmUp();
        Previewer preview = null;
        if (reader instanceof LineReaderImpl impl) {
            impl.setParser(new ShellParser(completions));
            impl.setCompleter(ShellCompletion.completer());
            impl.setHighlighter(ShellCompletion.highlighter(interpreter));
        }
        if (reader instanceof PreviewLineReader previewReader && interactive) {
            preview = new Previewer(previewReader, interpreter, completions, terminal::getWidth, colors);
            preview.bind(this::prompt);
        }
        this.previewer = preview;
        Perfer perf = null;
        if (interactive && reader instanceof LineReaderImpl impl) {
            perf = new Perfer(interpreter, preview);
            perf.bind(impl, this::prompt);
            if (preview != null) {
                preview.onEnter(perf::off);
            }
        }
        this.perfer = perf;
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

    /**
     * Consent for the modules that use native code (FR-77): answers recorded in {@code configFile}. On a real terminal
     * the user is asked for the missing ones; on a "dumb" terminal (JLine's type for input and output that are not a
     * terminal: a pipe, a file, a script) nobody is asked and the module is not loaded without a recorded answer.
     */
    public void nativeAccess(Path configFile) {
        boolean interactive = !terminal.getType().startsWith("dumb");
        interpreter.nativeAccess(new NativeAccessConsent(new ConfigFile(configFile),
                interactive ? new TerminalAsker(terminal) : null, line -> {
                    out.println(line);
                    out.flush();
                }));
    }

    /** Loads third-party modules (§4.4); warnings are displayed below the banner. */
    public void loadModules(Path dir) {
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
            }
            switch (supervisor.run(line, () -> execute(line))) {
                case Outcome.Success _ -> { }
                case Outcome.Failure(var error) -> printError(error.message());
                case Outcome.Cancelled() -> printError("^C");
                case Outcome.Abandoned(var commandLine) ->
                        printError(Messages.get("command.abandoned", commandLine));
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

    /** {@code PJ <dir>> }, {@code PJ? <dir>> } in preview mode (FR-66), {@code PJ% <dir>> } in perf mode (FR-76). */
    String prompt() {
        String mode = "PJ ";
        if (previewer != null && previewer.on()) {
            mode = marker("PJ?", AttributedStyle.CYAN);
        } else if (perfer != null && perfer.on()) {
            mode = marker("PJ%", AttributedStyle.YELLOW);
        }
        return mode + session.currentDirectory() + "> ";
    }

    private String marker(String text, int color) {
        return colors ? new AttributedString(text, AttributedStyle.DEFAULT.foreground(color)).toAnsi(terminal) + " "
                : text + " ";
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
