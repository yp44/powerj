package io.powerj.core.exec;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Métadonnées de la dernière commande native (spécification FR-38), accessibles par {@code $last}.
 * Elles ne sont jamais injectées dans le flux de sortie.
 *
 * @param command  chemin absolu de l'exécutable
 * @param args     arguments passés
 * @param pid      identifiant du process
 * @param exitCode code retour, ou {@code null} pour une application graphique lancée détachée (FR-39)
 * @param duration durée d'exécution (jusqu'au lancement pour une application détachée)
 */
public record NativeRun(Path command, List<String> args, long pid, Integer exitCode, Duration duration) {

    public NativeRun {
        Objects.requireNonNull(command, "command");
        args = List.copyOf(args);
        Objects.requireNonNull(duration, "duration");
    }

    /** Succès : code 0, ou application détachée. */
    public boolean succeeded() {
        return exitCode == null || exitCode == 0;
    }
}
