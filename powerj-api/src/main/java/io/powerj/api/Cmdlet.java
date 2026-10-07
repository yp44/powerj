package io.powerj.api;

/**
 * Commande PowerJ écrite en Java. Le shell construit le record de paramètres {@code P} à partir des
 * options saisies ({@link Option}), puis appelle {@link #begin}, {@link #process} pour chaque objet reçu
 * du pipeline, et {@link #end}.
 *
 * @param <P> record des paramètres
 * @param <I> type des objets reçus du pipeline ({@link Void} si le cmdlet n'en lit pas)
 * @param <O> type des objets produits ; un {@code record} est recommandé (affichage en tableau,
 *            complétion des propriétés)
 */
public interface Cmdlet<P extends Record, I, O> {

    /** Appelé une fois, avant tout objet reçu. Un cmdlet qui produit des objets le fait souvent ici. */
    default void begin(P params, CmdletContext<O> context) throws Exception {
    }

    /** Appelé pour chaque objet reçu du pipeline. */
    default void process(P params, I input, CmdletContext<O> context) throws Exception {
    }

    /** Appelé une fois, après le dernier objet reçu. */
    default void end(P params, CmdletContext<O> context) throws Exception {
    }
}
