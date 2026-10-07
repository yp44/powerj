package io.powerj.core.exec;

import java.io.PrintWriter;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Sorties du shell vues par l'interpréteur.
 *
 * @param out         flux de sortie (valeurs affichées)
 * @param errors      flux d'erreur PowerJ : une ligne par message (affichée en rouge par le terminal)
 * @param interactive {@code true} si un vrai terminal est attaché : les commandes natives en fin de ligne
 *                    héritent alors directement de la console (couleurs, programmes interactifs)
 */
public record ShellIo(PrintWriter out, Consumer<String> errors, boolean interactive) {

    public ShellIo {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(errors, "errors");
    }
}
