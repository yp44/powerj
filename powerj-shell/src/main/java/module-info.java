/**
 * Shell interactif PowerJ : boucle de lecture, édition de ligne, historique, point d'entrée.
 */
module io.powerj.shell {
    requires io.powerj.core;
    requires io.powerj.cmdlets;
    requires java.logging;
    requires org.jline.reader;
    // Fournisseur de terminal natif (console Windows, pty Unix) via l'API FFM, chargé par ServiceLoader.
    requires org.jline.terminal.ffm;

    exports io.powerj.shell;
}
