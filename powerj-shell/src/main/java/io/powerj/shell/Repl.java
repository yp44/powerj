package io.powerj.shell;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;

import io.powerj.core.BuildInfo;
import io.powerj.core.exec.Outcome;
import io.powerj.core.exec.PjException;
import io.powerj.core.exec.Supervisor;

/**
 * Boucle de lecture-exécution du shell : lit une ligne avec JLine (historique, Ctrl+R…), l'exécute
 * sous {@link Supervisor} et affiche le résultat. Ne laisse jamais échapper d'exception.
 */
public final class Repl {

    private final LineReader reader;
    private final Supervisor supervisor;
    private final Supplier<Path> currentDirectory;
    private final PrintWriter out;

    public Repl(LineReader reader, Supervisor supervisor, Supplier<Path> currentDirectory) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.supervisor = Objects.requireNonNull(supervisor, "supervisor");
        this.currentDirectory = Objects.requireNonNull(currentDirectory, "currentDirectory");
        this.out = reader.getTerminal().writer();
        // Ctrl+C pendant l'exécution annule la commande sans quitter le shell (FR-03) ;
        // pendant la saisie, JLine lève UserInterruptException.
        reader.getTerminal().handle(Terminal.Signal.INT, _ -> supervisor.cancel());
    }

    /**
     * Exécute la boucle jusqu'à {@code exit} ou Ctrl+D sur une ligne vide.
     *
     * @return le code retour du shell
     */
    public int run(BuildInfo buildInfo) {
        out.println(buildInfo.banner());
        out.flush();
        while (true) {
            String line;
            try {
                line = reader.readLine(prompt());
            } catch (UserInterruptException _) {
                continue; // Ctrl+C pendant la saisie : la ligne est effacée
            } catch (EndOfFileException _) {
                return 0; // Ctrl+D sur une ligne vide, ou fin de l'entrée
            }
            var command = Command.parse(line);
            if (command instanceof Command.Exit(int code)) {
                return code;
            }
            switch (supervisor.run(line, () -> execute(command))) {
                case Outcome.Success(var values) -> values.forEach(out::println);
                case Outcome.Failure(var error) -> out.println(error.message());
                case Outcome.Cancelled() -> out.println("^C");
                case Outcome.Abandoned(var commandLine) ->
                        out.println("commande abandonnée, elle continue en arrière-plan : " + commandLine);
            }
            out.flush();
        }
    }

    String prompt() {
        return "PJ " + currentDirectory.get() + "> ";
    }

    private List<Object> execute(Command command) throws Exception {
        return switch (command) {
            case Command.Empty() -> List.of();
            case Command.History(boolean clear) when clear -> {
                reader.getHistory().purge();
                yield List.of();
            }
            case Command.History _ -> historyListing();
            case Command.Invalid(var message) -> throw new PjException(message);
            case Command.Unknown(var name) -> throw new PjException("commande inconnue : " + name);
            case Command.Exit _ -> throw new IllegalStateException("exit est traité par la boucle");
        };
    }

    /** Entrées numérotées à partir de 1, numéros réutilisables avec {@code !n}. */
    private List<Object> historyListing() {
        List<Object> lines = new ArrayList<>();
        for (var entry : reader.getHistory()) {
            lines.add("%5d  %s".formatted(entry.index() + 1, entry.line()));
        }
        return lines;
    }
}
