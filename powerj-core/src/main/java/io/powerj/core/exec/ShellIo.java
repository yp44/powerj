package io.powerj.core.exec;

import java.io.PrintWriter;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Sorties du shell vues par l'interpréteur.
 *
 * @param out         flux de sortie (valeurs affichées)
 * @param errors      flux d'erreur PowerJ : une ligne par message (affichée en rouge par le terminal)
 * @param width       largeur du terminal en caractères, pour les tableaux
 * @param interactive {@code true} si un vrai terminal est attaché : les commandes natives en fin de ligne
 *                    héritent alors directement de la console (couleurs, programmes interactifs)
 */
public record ShellIo(PrintWriter out, Consumer<String> errors, boolean interactive, IntSupplier width) {

    public ShellIo {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(errors, "errors");
        Objects.requireNonNull(width, "width");
    }

    /** Sorties sans terminal réel : largeur fixe de 120 colonnes. */
    public ShellIo(PrintWriter out, Consumer<String> errors, boolean interactive) {
        this(out, errors, interactive, () -> 120);
    }
}
