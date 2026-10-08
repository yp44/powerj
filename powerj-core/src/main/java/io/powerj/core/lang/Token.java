package io.powerj.core.lang;

/** Élément lexical d'une ligne de commande. */
public sealed interface Token {

    /** Mot non quoté, pris tel quel (l'antislash n'y échappe rien, spécification FR-32b). */
    record Word(String text) implements Token { }

    /**
     * Expression : chaîne, variable, littéral, bloc {@code { … }}, groupe {@code ( … )}, appel Java
     * ({@code Math.max(3, 7)}, {@code new File("x")}), analysée par {@link ExpressionParser}.
     */
    record Expr(Ast.Expression expression) implements Token { }

    /** {@code $nom =} en tête d'instruction : affectation. */
    record AssignTo(String variable) implements Token { }

    /** Séparateur d'instructions {@code ;}, {@code &&} ou {@code ||}. */
    record Separator(Connector connector) implements Token { }

    /** {@code |} (pipeline). */
    record Pipe() implements Token { }

    /** Redirection {@code >}, {@code >>}, {@code 2>}, {@code 2>>}, ou {@code 2>&1} ({@link Stream#ERR_TO_OUT}). */
    record Redirection(Stream stream, boolean append) implements Token { }

    /** Flux redirigé ; {@code ERR_TO_OUT} : les erreurs rejoignent la sortie ({@code 2>&1}). */
    enum Stream { OUT, ERR, ERR_TO_OUT }
}
