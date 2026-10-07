package io.powerj.core.lang;

import java.util.List;

/** Élément lexical d'une ligne de commande. */
public sealed interface Token {

    /** Mot non quoté, pris tel quel (l'antislash n'y échappe rien, spécification FR-32b). */
    record Word(String text) implements Token { }

    /** Chaîne entre guillemets, échappements Java déjà décodés, avec ses variables interpolées. */
    record Str(List<StringPart> parts) implements Token { }

    /** Référence de variable avec ses accès : {@code $last.duration}, {@code $l[0]}. */
    record Var(String name, List<Accessor> accessors) implements Token { }

    /** {@code =} d'une affectation. */
    record Assign() implements Token { }

    /** Séparateur d'instructions {@code ;}, {@code &&} ou {@code ||}. */
    record Separator(Connector connector) implements Token { }

    /** {@code (} ouvrant une sous-expression. */
    record Open() implements Token { }

    /** {@code )} fermant une sous-expression, avec les accès qui la suivent : {@code (ls).name}. */
    record Close(List<Accessor> accessors) implements Token { }

    /** {@code |} (pipeline). */
    record Pipe() implements Token { }

    /** Redirection {@code >}, {@code >>}, {@code 2>}, {@code 2>>}. */
    record Redirection(Stream stream, boolean append) implements Token { }

    /** Flux redirigé. */
    enum Stream { OUT, ERR }
}
