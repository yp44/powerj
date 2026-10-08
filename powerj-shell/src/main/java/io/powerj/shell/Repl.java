package io.powerj.shell;

import java.io.PrintWriter;
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

import io.powerj.core.BuildInfo;
import io.powerj.core.exec.CmdletRegistry;
import io.powerj.core.exec.Completions;
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
    private List<String> moduleWarnings = List.of();

    public Repl(LineReader reader, Supervisor supervisor, Session session, CmdletRegistry registry) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
        this.session = Objects.requireNonNull(session, "session");
        this.terminal = reader.getTerminal();
        this.out = terminal.writer();
        boolean interactive = !terminal.getType().startsWith("dumb");
        var io = new ShellIo(out, this::printError, interactive, () -> terminal.getWidth());
        this.interpreter = new Interpreter(session, io, Map.of("history", this::history), registry);
        // Complétion (Tab) et coloration de la saisie (FR-08, FR-21 à FR-26).
        var completions = new Completions(interpreter);
        completions.warmUp();
        if (reader instanceof LineReaderImpl impl) {
            impl.setParser(new ShellParser(completions));
            impl.setCompleter(ShellCompletion.completer());
            impl.setHighlighter(ShellCompletion.highlighter(interpreter));
        }
        // Ctrl+C pendant l'exécution annule la commande sans quitter le shell (FR-03) ;
        // pendant la saisie, JLine lève UserInterruptException.
        terminal.handle(Terminal.Signal.INT, _ -> supervisor.cancel());
    }

    /** Loads third-party modules (§4.4); warnings are displayed below the banner. */
    public void loadModules(java.nio.file.Path dir) {
        moduleWarnings = interpreter.loadModules(dir);
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
        while (session.exitRequest().isEmpty()) {
            String line;
            try {
                line = reader.readLine(prompt());
            } catch (UserInterruptException _) {
                continue; // Ctrl+C pendant la saisie : la ligne est effacée
            } catch (EndOfFileException _) {
                return 0; // Ctrl+D sur une ligne vide, ou fin de l'entrée
            }
            switch (supervisor.run(line, () -> execute(line))) {
                case Outcome.Success _ -> { }
                case Outcome.Failure(var error) -> printError(error.message());
                case Outcome.Cancelled() -> printError("^C");
                case Outcome.Abandoned(var commandLine) ->
                        printError("commande abandonnée, elle continue en arrière-plan : " + commandLine);
            }
            out.flush();
        }
        return session.exitRequest().getAsInt();
    }

    String prompt() {
        return "PJ " + session.currentDirectory() + "> ";
    }

    private List<Object> execute(String line) throws InterruptedException {
        var trimmed = line.strip();
        // Une expansion d'historique réussie ne laisse jamais de « ! » en tête de ligne (FR-11).
        if (trimmed.startsWith("!") && trimmed.length() > 1 && !Character.isWhitespace(trimmed.charAt(1))) {
            throw new PjException("historique : aucune commande ne correspond à " + trimmed.split("\\s+")[0]);
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
            throw new PjException("history : option inconnue '" + Values.text(args.getLast())
                    + "' (option disponible : --clear)");
        }
        List<Object> lines = new ArrayList<>();
        for (var entry : reader.getHistory()) {
            lines.add("%5d  %s".formatted(entry.index() + 1, entry.line()));
        }
        return lines;
    }

    /** Line of the PowerJ error stream, in red on a terminal that supports colors. */
    private void printError(String message) {
        synchronized (out) {
            out.println(new AttributedString(message, AttributedStyle.DEFAULT.foreground(AttributedStyle.RED))
                    .toAnsi(terminal));
            out.flush();
        }
    }
}
