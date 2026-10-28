package io.powerj.core.lang;

import java.util.List;

/** Lexical element of a command line. */
public sealed interface Token {

    /** Unquoted word, taken as is (backslash escapes nothing in it, specification FR-32b). */
    record Word(String text) implements Token { }

    /**
     * Expression: string, variable, literal, block {@code { … }}, group {@code ( … )}, Java call
     * ({@code Math.max(3, 7)}, {@code new File("x")}), parsed by {@link ExpressionParser}.
     */
    record Expr(Ast.Expression expression) implements Token { }

    /**
     * Word made of pieces written without a blank between them ({@code "my docs"/*}, {@code $dir/a.txt},
     * {@code --message="a b"}): at least two {@link Word} or {@link Expr} tokens, in argument position (FR-32c).
     */
    record Compound(List<Token> pieces) implements Token {
        public Compound {
            pieces = List.copyOf(pieces);
        }
    }

    /** {@code $name =} at the start of a statement: assignment. */
    record AssignTo(String variable) implements Token { }

    /** Statement separator {@code ;}, {@code &&} or {@code ||}. */
    record Separator(Connector connector) implements Token { }

    /** {@code &} ending a statement: it runs in the background (FR-75), and the next one follows at once. */
    record Background() implements Token { }

    /** {@code |} (pipeline). */
    record Pipe() implements Token { }

    /** Redirection {@code >}, {@code >>}, {@code 2>}, {@code 2>>}, or {@code 2>&1} ({@link RedirectedStream#ERR_TO_OUT}). */
    record Redirection(RedirectedStream stream, boolean append) implements Token { }

}
