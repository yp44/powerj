package io.powerj.core.lang;

import java.util.List;
import java.util.Optional;

/** Syntax tree of a line. */
public final class Ast {

    private Ast() {
    }

    /** Complete line: sequence of statements chained by {@code ;}, {@code &&}, {@code ||}. */
    public record Script(List<Step> steps) {
        public Script {
            steps = List.copyOf(steps);
        }
    }

    /** Statement and how it is chained to the previous one. */
    public record Step(Connector connector, Statement statement) { }

    /**
     * Statement: optional assignment and pipeline.
     *
     * @param assignTo assigned variable ({@code $x = …}), if present
     */
    public record Statement(Optional<String> assignTo, Pipeline pipeline) {

        /** Body of the first stage (shortcut for a statement without a pipeline). */
        public Body body() {
            return pipeline.stages().getFirst().body();
        }

        public List<Redirect> redirects() {
            return pipeline.redirects();
        }
    }

    /**
     * Stages connected by {@code |}, and redirections of the whole ({@code >} applies to the last stage,
     * {@code 2>} to the errors of all stages).
     */
    public record Pipeline(List<Stage> stages, List<Redirect> redirects) {
        public Pipeline {
            stages = List.copyOf(stages);
            redirects = List.copyOf(redirects);
        }
    }

    /**
     * Stage of a pipeline.
     *
     * @param errorsToOutput {@code 2>&1}: the stage's error stream joins its output
     */
    public record Stage(Body body, boolean errorsToOutput) { }

    /** Body of a statement: command or expression. */
    public sealed interface Body { }

    /**
     * Command and its arguments.
     *
     * @param forceNative {@code true} if prefixed with {@code ^} (FR-14)
     */
    public record Command(String name, boolean forceNative, List<Argument> arguments) implements Body {
        public Command {
            arguments = List.copyOf(arguments);
        }
    }

    /** Expression alone on the line: {@code $exit}, {@code "texte"}, {@code 42}. */
    public record ExpressionBody(Expression expression) implements Body { }

    /** Command argument. */
    public sealed interface Argument { }

    /** Unquoted word, taken as is. */
    public record WordArgument(String text) implements Argument { }

    /** Computed argument: quoted string or variable. */
    public record ExpressionArgument(Expression expression) implements Argument { }

    /** Expression: value of an argument, of a statement or of a block {@code { … }}. */
    public sealed interface Expression { }

    public record Literal(Object value) implements Expression { }

    public record StringExpression(List<StringPart> parts) implements Expression { }

    public record VariableExpression(String name, List<Accessor> accessors) implements Expression { }

    /** Parenthesized pipeline whose value is taken: {@code (ls)}, {@code (ls | where {…})}. */
    public record SubExpression(Pipeline pipeline) implements Expression { }

    /**
     * Bare name in an expression: class ({@code Math}, {@code LocalDate}) or start of a qualified name
     * ({@code java} in {@code java.util.List.of(…)}), resolved at evaluation time (FR-46, FR-47).
     */
    public record Name(String name) implements Expression { }

    /** {@code new Classe(arguments)} (FR-48). */
    public record New(String type, List<Expression> arguments) implements Expression {
        public New {
            arguments = List.copyOf(arguments);
        }
    }

    /** Explicit conversion {@code [type] valeur} (FR-50). */
    public record Cast(String type, Expression operand) implements Expression { }

    /**
     * Block {@code { … }} with no declared parameter: its value is a {@link io.powerj.api.ScriptBlock} evaluated
     * later, the received object being {@code $_}.
     */
    public record BlockExpression(String source, Expression body) implements Expression { }

    /**
     * Java-style lambda (FR-33b): {@code { f -> f.size > 1mb }}, {@code (a, b) -> a.compareTo(b)} within the
     * parentheses of a Java call.
     */
    public record Lambda(String source, List<String> parameters, Expression body) implements Expression {
        public Lambda {
            parameters = List.copyOf(parameters);
        }
    }

    /** Method reference: {@code String::length}, {@code $x::equals}, {@code ArrayList::new} (FR-33b). */
    public record MethodRef(Expression target, String method) implements Expression { }

    /** {@code cible.nom}: property (FR-28). */
    public record Get(Expression target, String name) implements Expression { }

    /** {@code liste*.nom}: the property of each element ("spread" operator, as in Groovy). */
    public record SpreadGet(Expression target, String name) implements Expression { }

    /** {@code liste*.méthode(arguments)}: the method called on each element. */
    public record SpreadInvoke(Expression target, String method, List<Expression> arguments) implements Expression {
        public SpreadInvoke {
            arguments = List.copyOf(arguments);
        }
    }

    /** {@code cible[index]}. */
    public record At(Expression target, Expression index) implements Expression { }

    /** {@code cible.méthode(arguments)}: Java instance method call. */
    public record Invoke(Expression target, String method, List<Expression> arguments) implements Expression {
        public Invoke {
            arguments = List.copyOf(arguments);
        }
    }

    /** Java binary operation (FR-33). */
    public record Binary(Operator operator, Expression left, Expression right) implements Expression { }

    /** {@code !x} or {@code -x}. */
    public record Unary(Operator operator, Expression operand) implements Expression { }

    /** {@code condition ? siVrai : siFaux}. */
    public record Conditional(Expression condition, Expression whenTrue, Expression whenFalse) implements Expression { }

    /** List {@code [1, 2, 3]}. */
    public record ListLiteral(List<Expression> elements) implements Expression {
        public ListLiteral {
            elements = List.copyOf(elements);
        }
    }

    /** {@code now}: instant of evaluation. */
    public record Now() implements Expression { }

    /** Expression operators, with their symbol. */
    public enum Operator {
        OR("||"), AND("&&"), EQ("=="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">="),
        ADD("+"), SUB("-"), MUL("*"), DIV("/"), REM("%"), NOT("!"), NEG("-");

        private final String symbol;

        Operator(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }
    }

    /** Redirection of a stream to a file. */
    public record Redirect(Token.Stream stream, boolean append, Argument target) { }
}
