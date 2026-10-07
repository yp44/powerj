package io.powerj.shell;

import java.io.PrintWriter;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import io.powerj.core.exec.CmdletRegistry;
import io.powerj.core.exec.Interpreter;
import io.powerj.core.exec.NativeRun;
import io.powerj.core.exec.Outcome;
import io.powerj.core.exec.Session;
import io.powerj.core.exec.ShellIo;
import io.powerj.core.exec.Supervisor;

/**
 * Mode non interactif (spécification FR-04d) : {@code powerj -c "ligne"} ou {@code powerj fichier.pj}. Pas de
 * prompt ni d'historique ; une erreur bloquante arrête l'exécution.
 * <p>
 * Code retour : celui de {@code exit n} s'il est appelé ; sinon 0 si la dernière ligne a réussi, le code de
 * la dernière commande native si c'est elle qui a échoué, 1 pour une erreur bloquante PowerJ.
 */
final class ScriptMode {

    private final Session session;
    private final Interpreter interpreter;
    private final Consumer<String> errors;
    private final Supervisor supervisor = new Supervisor();

    /**
     * @param input lignes de l'entrée standard quand ce n'est pas un terminal : elles alimentent la première
     *              étape qui lit des objets ({@code dir /b | powerj -c "where { … }"})
     */
    ScriptMode(Session session, CmdletRegistry registry, PrintWriter out, Consumer<String> errors, int width,
               Optional<Iterator<String>> input) {
        this.session = session;
        this.errors = errors;
        // Les commandes natives écrivent directement sur la sortie du process, même redirigée.
        this.interpreter = new Interpreter(session, new ShellIo(out, errors, true, () -> width), Map.of(), registry);
        input.ifPresent(interpreter::useStandardInput);
    }

    /** Exécute les lignes dans l'ordre ; les lignes vides et les commentaires {@code #} sont ignorés. */
    int run(List<String> lines) {
        int code = 0;
        for (String line : lines) {
            if (line.isBlank() || line.strip().startsWith("#")) {
                continue;
            }
            NativeRun nativeBefore = session.lastNative().orElse(null);
            Outcome outcome = supervisor.run(line, () -> {
                interpreter.execute(line);
                return List.of();
            });
            if (session.exitRequest().isPresent()) {
                return session.exitRequest().getAsInt();
            }
            switch (outcome) {
                case Outcome.Success _ -> { }
                case Outcome.Failure(var error) -> {
                    errors.accept(error.message());
                    return 1;
                }
                case Outcome.Cancelled() -> {
                    return 130;
                }
                case Outcome.Abandoned _ -> {
                    return 1;
                }
            }
            if (session.lastSucceeded()) {
                code = 0;
            } else if (interpreter.hadBlockingError()) {
                return 1;
            } else {
                NativeRun nativeAfter = session.lastNative().orElse(null);
                code = nativeAfter != null && nativeAfter != nativeBefore && nativeAfter.exitCode() != null
                        && nativeAfter.exitCode() != 0 ? nativeAfter.exitCode() : 1;
            }
        }
        return code;
    }
}
