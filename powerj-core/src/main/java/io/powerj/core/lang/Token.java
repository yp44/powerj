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

    /** Bloc {@code { … }} : texte source entre les accolades, analysé ensuite par {@link ExpressionParser}. */
    record Block(String source) implements Token { }

    /** {@code |} (pipeline). */
    record Pipe() implements Token { }

    /** Redirection {@code >}, {@code >>}, {@code 2>}, {@code 2>>}, ou {@code 2>&1} ({@link Stream#ERR_TO_OUT}). */
    record Redirection(Stream stream, boolean append) implements Token { }

    /** Flux redirigé ; {@code ERR_TO_OUT} : les erreurs rejoignent la sortie ({@code 2>&1}). */
    enum Stream { OUT, ERR, ERR_TO_OUT }
}
