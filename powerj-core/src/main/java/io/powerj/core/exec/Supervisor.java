package io.powerj.core.exec;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Exécute une commande en capturant tout {@link Throwable} (spécification FR-56), pour que rien de ce
 * qu'exécute une ligne ne puisse faire tomber le shell.
 */
public final class Supervisor {

    private static final Logger LOG = Logger.getLogger(Supervisor.class.getName());

    /** Réserve libérée sur {@link OutOfMemoryError} pour laisser au shell de quoi continuer. */
    private static final int MEMORY_RESERVE_BYTES = 8 * 1024 * 1024;

    /** Travail exécuté sous supervision. */
    @FunctionalInterface
    public interface Task {
        List<Object> execute() throws Exception;
    }

    private byte[] memoryReserve = new byte[MEMORY_RESERVE_BYTES];
    private volatile Thread runner;

    /**
     * Exécute la tâche et en renvoie le résultat ; ne lève jamais d'exception.
     *
     * @param commandLine ligne saisie, pour le journal et les messages
     */
    public Outcome run(String commandLine, Task task) {
        runner = Thread.currentThread();
        try {
            return new Outcome.Success(task.execute());
        } catch (Throwable t) {
            return failureOf(commandLine, t);
        } finally {
            runner = null;
            Thread.interrupted(); // une annulation arrivée en fin de commande ne doit pas toucher la suivante
            restoreMemoryReserve();
        }
    }

    /** Demande l'annulation de la commande en cours (Ctrl+C) ; sans effet si aucune ne tourne. */
    public void cancel() {
        var current = runner;
        if (current != null) {
            current.interrupt();
        }
    }

    private Outcome failureOf(String commandLine, Throwable t) {
        return switch (t) {
            case InterruptedException _, CancellationException _ -> new Outcome.Cancelled();
            case PjException e -> new Outcome.Failure(e.error());
            case OutOfMemoryError e -> {
                memoryReserve = null;
                yield new Outcome.Failure(PjError.of(
                        "mémoire insuffisante : filtrez les données plus tôt dans le pipeline", e));
            }
            case StackOverflowError e -> new Outcome.Failure(PjError.of("récursion trop profonde", e));
            case LinkageError e -> new Outcome.Failure(PjError.of(
                    "classe inutilisable : " + e.getMessage(), e));
            default -> {
                LOG.log(Level.SEVERE, "Erreur interne en exécutant : " + commandLine, t);
                yield new Outcome.Failure(PjError.of(
                        "erreur interne : " + t + " (détails dans le journal)", t));
            }
        };
    }

    private void restoreMemoryReserve() {
        if (memoryReserve == null) {
            try {
                memoryReserve = new byte[MEMORY_RESERVE_BYTES];
            } catch (OutOfMemoryError _) {
                // Toujours pas de mémoire : on réessaiera après la prochaine commande.
            }
        }
    }
}
