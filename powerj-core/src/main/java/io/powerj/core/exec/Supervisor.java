package io.powerj.core.exec;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
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

    /** Pile des fils d'exécution : de la marge pour les expressions profondes. */
    private static final long STACK_SIZE = 16L * 1024 * 1024;

    /** Commande en cours : son fil, son résultat, et le nombre de Ctrl+C reçus. */
    private record Running(Thread worker, CompletableFuture<Outcome> outcome, String commandLine, AtomicInteger cancels) { }

    private byte[] memoryReserve = new byte[MEMORY_RESERVE_BYTES];
    private volatile Running running;

    /**
     * Exécute la tâche dans un fil dédié et en renvoie le résultat ; ne lève jamais d'exception. Si la tâche
     * ne réagit pas au premier Ctrl+C, le second l'abandonne (FR-57) : le résultat est
     * {@link Outcome.Abandoned} et la tâche continue en arrière-plan, sans retenir le shell.
     *
     * @param commandLine ligne saisie, pour le journal et les messages
     */
    public Outcome run(String commandLine, Task task) {
        var outcome = new CompletableFuture<Outcome>();
        Thread worker = Thread.ofPlatform().name("powerj-commande").daemon().stackSize(STACK_SIZE).unstarted(() -> {
            Outcome result;
            try {
                result = new Outcome.Success(task.execute());
            } catch (Throwable t) {
                result = failureOf(commandLine, t);
            } finally {
                restoreMemoryReserve();
            }
            outcome.complete(result);
        });
        running = new Running(worker, outcome, commandLine, new AtomicInteger());
        try {
            worker.start();
            return outcome.join();
        } finally {
            running = null;
        }
    }

    /**
     * Ctrl+C : le premier demande l'annulation de la commande en cours (interruption) ; le suivant
     * l'abandonne si elle ne s'est pas arrêtée. Sans effet si aucune commande ne tourne.
     */
    public void cancel() {
        var current = running;
        if (current == null) {
            return;
        }
        if (current.cancels().incrementAndGet() == 1) {
            current.worker().interrupt();
        } else {
            LOG.warning(() -> "Commande abandonnée : " + current.commandLine());
            current.outcome().complete(new Outcome.Abandoned(current.commandLine()));
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
