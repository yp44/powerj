package io.powerj.core.exec;

import java.util.List;
import java.util.Objects;

/**
 * Résultat de l'exécution d'une ligne (spécification FR-56). Le REPL ne voit jamais d'exception :
 * il traite exhaustivement ces quatre cas.
 */
public sealed interface Outcome {

    /** La commande s'est terminée normalement et a produit ces valeurs. */
    record Success(List<Object> values) implements Outcome {
        public Success {
            values = List.copyOf(values);
        }
    }

    /** La commande a échoué. */
    record Failure(PjError error) implements Outcome {
        public Failure {
            Objects.requireNonNull(error, "error");
        }
    }

    /** La commande a été annulée par Ctrl+C. */
    record Cancelled() implements Outcome { }

    /** La commande ne répondait plus à l'annulation et a été abandonnée (FR-57, niveau 3). */
    record Abandoned(String commandLine) implements Outcome {
        public Abandoned {
            Objects.requireNonNull(commandLine, "commandLine");
        }
    }
}
