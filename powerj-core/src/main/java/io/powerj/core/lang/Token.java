package io.powerj.core.lang;

/** Lexical element of a command line. */
public sealed interface Token {

    /** Unquoted word, taken as is (backslash escapes nothing in it, specification FR-32b). */
    record Word(String text) implements Token { }

    /**
     * Expression: string, variable, literal, block {@code { … }}, group {@code ( … )}, Java call
     * ({@code Math.max(3, 7)}, {@code new File("x")}), parsed by {@link ExpressionParser}.
     */
    record Expr(Ast.Expression expression) implements Token { }

    /** {@code $name =} at the start of a statement: assignment. */
    record AssignTo(String variable) implements Token { }

    /** Statement separator {@code ;}, {@code &&} or {@code ||}. */
    record Separator(Connector connector) implements Token { }

    /** {@code |} (pipeline). */
    record Pipe() implements Token { }

    /** Redirection {@code >}, {@code >>}, {@code 2>}, {@code 2>>}, or {@code 2>&1} ({@link Stream#ERR_TO_OUT}). */
    record Redirection(Stream stream, boolean append) implements Token { }

    /** Redirected stream; {@code ERR_TO_OUT}: errors join the output ({@code 2>&1}). */
    enum Stream { OUT, ERR, ERR_TO_OUT }
}
