package io.powerj.api;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Ce que le shell met à disposition d'un cmdlet pendant son exécution.
 *
 * @param <O> type des objets produits
 */
public interface CmdletContext<O> {

    /**
     * Écrit un objet sur le flux de sortie. Si l'utilisateur a demandé l'annulation (Ctrl+C), lève
     * une exception qui arrête proprement le cmdlet.
     */
    void emit(O value);

    /** Signale une erreur non bloquante : le cmdlet continue (selon l'option commune {@code --on-error}). */
    void error(String message);

    /** Répertoire courant de la session, base des chemins relatifs. */
    Path currentDirectory();

    /** Environnement de la session (modifiable), transmis aux commandes natives lancées ensuite. */
    Map<String, String> environment();

    /** Valeur d'une variable de la session. */
    Optional<Object> variable(String name);

    /** {@code true} si l'utilisateur a demandé l'annulation (Ctrl+C). */
    boolean cancelled();

    /**
     * Compile une expression PowerJ en bloc, comme si elle avait été saisie entre accolades.
     * Ex. {@code compile("$_.size > 10kb")}.
     *
     * @throws IllegalArgumentException si l'expression est syntaxiquement invalide
     */
    ScriptBlock compile(String expression);
}
