package io.powerj.api;

import java.util.Collection;
import java.util.Map;

/**
 * Bloc d'expression {@code { … }} saisi par l'utilisateur, évalué pour un objet courant {@code $_}
 * (spécification FR-33, FR-36). Exemple : {@code { $_.size > 1mb && !$_.dir }}.
 */
public interface ScriptBlock {

    /** Évalue le bloc avec {@code $_} égal à {@code current}. */
    Object invoke(Object current);

    /** Texte source du bloc, sans les accolades. */
    String source();

    /** Évalue le bloc et interprète le résultat comme une condition ({@link #isTrue(Object)}). */
    default boolean test(Object current) {
        return isTrue(invoke(current));
    }

    /**
     * Vérité d'une valeur (FR-36) : {@code false}, {@code null}, {@code 0}, {@code ""}, une collection ou une
     * map vide sont faux ; tout le reste est vrai.
     */
    static boolean isTrue(Object value) {
        return switch (value) {
            case null -> false;
            case Boolean b -> b;
            case Number n -> n.doubleValue() != 0;
            case CharSequence s -> !s.isEmpty();
            case Collection<?> c -> !c.isEmpty();
            case Map<?, ?> m -> !m.isEmpty();
            default -> true;
        };
    }
}
