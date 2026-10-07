package io.powerj.shell;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStyle;

import io.powerj.core.BuildInfo;
import io.powerj.core.exec.Builtin;
import io.powerj.core.exec.Interpreter;
import io.powerj.core.exec.Outcome;
import io.powerj.core.exec.PjException;
import io.powerj.core.exec.Session;
import io.powerj.core.exec.ShellIo;
import io.powerj.core.exec.Supervisor;

/**
 * Boucle de lecture-exécution du shell : lit une ligne avec JLine (historique, Ctrl+R…), l'exécute
 * sous {@link Supervisor} et affiche le résultat. Ne laisse jamais échapper d'exception.
 */
public final class Repl {

    private final LineReader reader;
    private final Supervisor supervisor;
    private final Session session;
    private final Interpreter interpreter;
    private final Terminal terminal;
    private final PrintWriter out;

    public Repl(LineReader reader, Supervisor supervisor, Session session) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
        this.session = Objects.requireNonNull(session, "session");
        this.terminal = reader.getTerminal();
        this.out = terminal.writer();
        boolean interactive = !terminal.getType().startsWith("dumb");
        this.interpreter = new Interpreter(session, new ShellIo(out, this::printError, interactive),
                Map.of("history", this::history));
        // Ctrl+C pendant l'exécution annule la commande sans quitter le shell (FR-03) ;
        // pendant la saisie, JLine lève UserInterruptException.
        terminal.handle(Terminal.Signal.INT, _ -> supervisor.cancel());
    }

    /**
     * Exécute la boucle jusqu'à {@code exit} ou Ctrl+D sur une ligne vide.
     *
     * @return le code retour du shell
     */
    public int run(BuildInfo buildInfo) {
        out.println(buildInfo.banner());
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

    /** {@code history} (liste numérotée, numéros réutilisables avec {@code !n}) ou {@code history --clear}. */
    private List<Object> history(List<String> args, Session ignored) throws Exception {
        if (args.equals(List.of("--clear"))) {
            reader.getHistory().purge();
            return List.of();
        }
        if (!args.isEmpty()) {
            throw new PjException("history : option inconnue '" + args.getLast() + "' (option disponible : --clear)");
        }
        List<Object> lines = new ArrayList<>();
        for (var entry : reader.getHistory()) {
            lines.add("%5d  %s".formatted(entry.index() + 1, entry.line()));
        }
        return lines;
    }

    /** Ligne du flux d'erreur PowerJ, en rouge sur un terminal qui gère les couleurs. */
    private void printError(String message) {
        synchronized (out) {
            out.println(new AttributedString(message, AttributedStyle.DEFAULT.foreground(AttributedStyle.RED))
                    .toAnsi(terminal));
            out.flush();
        }
    }
}
