package io.powerj.shell;

import java.io.IOError;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;

import io.powerj.core.BuildInfo;
import io.powerj.core.error.PjException;
import io.powerj.core.runtime.Interpreter;
import io.powerj.core.runtime.Outcome;
import io.powerj.core.runtime.Session;
import io.powerj.core.runtime.Supervisor;

/**
 * Read-execute loop of the shell: reads a line (history, Ctrl+R… are the line editor's business), executes it under
 * {@link Supervisor} and displays the result. Never lets an exception escape. It does not build anything it uses:
 * {@link InteractiveShell} assembles the interpreter, the assistance, the modes and the prompt.
 */
public final class Repl {

    private static final Logger LOG = Logger.getLogger(Repl.class.getName());

    private final LineEditor editor;
    private final Supervisor supervisor;
    private final Session session;
    private final Interpreter interpreter;
    private final Terminal terminal;
    private final PrintWriter out;
    private final PromptRenderer prompt;
    private final ErrorPrinter errors;
    private final ProfileRunner profileRunner;
    /** Run after each line is read: what the line editor showed below the line (the preview) is stopped (FR-66). */
    private final Runnable afterInput;
    private List<String> moduleWarnings = List.of();
    private Path profile;
    private boolean runProfile;

    Repl(LineEditor editor, Terminal terminal, Supervisor supervisor, Session session, Interpreter interpreter,
         PromptRenderer prompt, ErrorPrinter errors, Runnable afterInput) {
        this.editor = Objects.requireNonNull(editor, "editor");
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
        this.session = Objects.requireNonNull(session, "session");
        this.interpreter = Objects.requireNonNull(interpreter, "interpreter");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        this.errors = Objects.requireNonNull(errors, "errors");
        this.afterInput = Objects.requireNonNull(afterInput, "afterInput");
        this.out = terminal.writer();
        this.profileRunner = new ProfileRunner(supervisor, session, this::execute, errors, out);
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
     * Runs the loop until {@code exit} or Ctrl+D on an empty line. Ctrl+C during execution cancels the command without
     * leaving the shell (FR-03); during input, the line editor reports it. The handler of the signal that was there is
     * put back when the loop ends.
     *
     * @return the shell's exit code
     */
    public int run(BuildInfo buildInfo) {
        var previous = terminal.handle(Terminal.Signal.INT, _ -> supervisor.cancel());
        try {
            return loop(buildInfo);
        } finally {
            terminal.handle(Terminal.Signal.INT, previous);
        }
    }

    private int loop(BuildInfo buildInfo) {
        out.println(buildInfo.banner()); // the banner, then the prompt (FR-01)
        moduleWarnings.forEach(errors::print);
        out.flush();
        profileRunner.run(profile, runProfile);
        while (session.exitRequest().isEmpty()) {
            String line;
            if (terminal.paused()) {
                terminal.resume(); // a command abandoned with a second Ctrl+C may still hold the console
            }
            session.jobs().drainFinished().forEach(out::println); // FR-75: background jobs ended meanwhile
            out.flush();
            try {
                line = editor.readLine(prompt.render());
            } catch (UserInterruptException _) {
                continue; // Ctrl+C during input: the line is cleared
            } catch (EndOfFileException _) {
                return 0; // Ctrl+D on an empty line, or end of input
            } catch (IOError e) {
                LOG.log(Level.WARNING, "The terminal failed while a line was read", e);
                return 1; // the terminal is gone (closed, disconnected): there is nobody to talk to
            } finally {
                afterInput.run();
            }
            switch (supervisor.run(line, () -> {
                execute(line);
                return List.of();
            })) {
                case Outcome.Success _ -> { }
                case Outcome.Failure(var error) -> errors.print(error.message());
                case Outcome.Cancelled() -> errors.print("^C");
                case Outcome.Abandoned(var commandLine) ->
                        errors.print(Messages.get("command.abandoned", commandLine));
            }
            out.flush();
        }
        return session.exitRequest().getAsInt();
    }

    private void execute(String line) throws InterruptedException {
        var trimmed = line.strip();
        // A successful history expansion never leaves a "!" at the start of the line (FR-11).
        if (trimmed.startsWith("!") && trimmed.length() > 1 && !Character.isWhitespace(trimmed.charAt(1))) {
            throw new PjException(Messages.get("history.noMatch", trimmed.split("\\s+")[0]));
        }
        interpreter.execute(line);
    }
}
