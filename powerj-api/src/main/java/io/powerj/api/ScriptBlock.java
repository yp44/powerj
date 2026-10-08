package io.powerj.api;

/**
 * Bloc d'expression saisi par l'utilisateur, évalué pour un objet reçu (spécification FR-33b, FR-36) :
 * lambda {@code { f -> f.size > 1mb && !f.dir }}, bloc à {@code $_} {@code { $_.dir }}, ou référence de
 * méthode {@code FileEntry::name}.
 */
public interface ScriptBlock {

    /** Évalue le bloc avec {@code $_} égal à {@code current}. */
    Object invoke(Object current);

    /** Texte source du bloc, sans les accolades. */
    String source();

    /**
     * Évalue le bloc comme une condition (FR-33b) : le résultat doit être un booléen, comme pour un
     * {@code Predicate} Java.
     *
     * @throws IllegalStateException si le bloc renvoie autre chose qu'un booléen
     */
    default boolean test(Object current) {
        Object value = invoke(current);
        if (value instanceof Boolean b) {
            return b;
        }
        throw new IllegalStateException("le bloc doit renvoyer un booléen, reçu "
                + (value == null ? "null" : value.getClass().getSimpleName() + " " + value));
    }
}
