package io.powerj.core.lang;

import java.util.List;
import java.util.Optional;

/** Arbre syntaxique d'une ligne. */
public final class Ast {

    private Ast() {
    }

    /** Ligne complète : suite d'instructions enchaînées par {@code ;}, {@code &&}, {@code ||}. */
    public record Script(List<Step> steps) {
        public Script {
            steps = List.copyOf(steps);
        }
    }

    /** Instruction et façon dont elle s'enchaîne à la précédente. */
    public record Step(Connector connector, Statement statement) { }

    /**
     * Instruction : affectation éventuelle et pipeline.
     *
     * @param assignTo variable affectée ({@code $x = …}), si présente
     */
    public record Statement(Optional<String> assignTo, Pipeline pipeline) {

        /** Corps de la première étape (raccourci pour une instruction sans pipeline). */
        public Body body() {
            return pipeline.stages().getFirst().body();
        }

        public List<Redirect> redirects() {
            return pipeline.redirects();
        }
    }

    /**
     * Étapes reliées par {@code |}, et redirections de l'ensemble ({@code >} s'applique à la dernière étape,
     * {@code 2>} aux erreurs de toutes les étapes).
     */
    public record Pipeline(List<Stage> stages, List<Redirect> redirects) {
        public Pipeline {
            stages = List.copyOf(stages);
            redirects = List.copyOf(redirects);
        }
    }

    /**
     * Étape d'un pipeline.
     *
     * @param errorsToOutput {@code 2>&1} : le flux d'erreur de l'étape rejoint sa sortie
     */
    public record Stage(Body body, boolean errorsToOutput) { }

    /** Corps d'une instruction : commande ou expression. */
    public sealed interface Body { }

    /**
     * Commande et ses arguments.
     *
     * @param forceNative {@code true} si préfixée par {@code ^} (FR-14)
     */
    public record Command(String name, boolean forceNative, List<Argument> arguments) implements Body {
        public Command {
            arguments = List.copyOf(arguments);
        }
    }

    /** Expression seule sur la ligne : {@code $exit}, {@code "texte"}, {@code 42}. */
    public record ExpressionBody(Expression expression) implements Body { }

    /** Argument de commande. */
    public sealed interface Argument { }

    /** Mot non quoté, pris tel quel. */
    public record WordArgument(String text) implements Argument { }

    /** Argument calculé : chaîne quotée ou variable. */
    public record ExpressionArgument(Expression expression) implements Argument { }

    /** Expression : valeur d'un argument, d'une instruction ou d'un bloc {@code { … }}. */
    public sealed interface Expression { }

    public record Literal(Object value) implements Expression { }

    public record StringExpression(List<StringPart> parts) implements Expression { }

    public record VariableExpression(String name, List<Accessor> accessors) implements Expression { }

    /** Pipeline entre parenthèses, dont on prend la valeur : {@code (ls)}, {@code (ls | where {…})}. */
    public record SubExpression(Pipeline pipeline) implements Expression { }

    /**
     * Nom nu dans une expression : classe ({@code Math}, {@code LocalDate}) ou début de nom qualifié
     * ({@code java} dans {@code java.util.List.of(…)}), résolu à l'évaluation (FR-46, FR-47).
     */
    public record Name(String name) implements Expression { }

    /** {@code new Classe(arguments)} (FR-48). */
    public record New(String type, List<Expression> arguments) implements Expression {
        public New {
            arguments = List.copyOf(arguments);
        }
    }

    /** Conversion explicite {@code [type] valeur} (FR-50). */
    public record Cast(String type, Expression operand) implements Expression { }

    /**
     * Bloc {@code { … }} sans paramètre déclaré : sa valeur est un {@link io.powerj.api.ScriptBlock} évalué plus
     * tard, l'objet reçu étant {@code $_}.
     */
    public record BlockExpression(String source, Expression body) implements Expression { }

    /**
     * Lambda à la Java (FR-33b) : {@code { f -> f.size > 1mb }}, {@code (a, b) -> a.compareTo(b)} entre les
     * parenthèses d'un appel Java.
     */
    public record Lambda(String source, List<String> parameters, Expression body) implements Expression {
        public Lambda {
            parameters = List.copyOf(parameters);
        }
    }

    /** Référence de méthode : {@code String::length}, {@code $x::equals}, {@code ArrayList::new} (FR-33b). */
    public record MethodRef(Expression target, String method) implements Expression { }

    /** {@code cible.nom} : propriété (FR-28). */
    public record Get(Expression target, String name) implements Expression { }

    /** {@code liste*.nom} : la propriété de chaque élément (opérateur « spread », comme en Groovy). */
    public record SpreadGet(Expression target, String name) implements Expression { }

    /** {@code liste*.méthode(arguments)} : la méthode appelée sur chaque élément. */
    public record SpreadInvoke(Expression target, String method, List<Expression> arguments) implements Expression {
        public SpreadInvoke {
            arguments = List.copyOf(arguments);
        }
    }

    /** {@code cible[index]}. */
    public record At(Expression target, Expression index) implements Expression { }

    /** {@code cible.méthode(arguments)} : appel de méthode d'instance Java. */
    public record Invoke(Expression target, String method, List<Expression> arguments) implements Expression {
        public Invoke {
            arguments = List.copyOf(arguments);
        }
    }

    /** Opération binaire Java (FR-33). */
    public record Binary(Operator operator, Expression left, Expression right) implements Expression { }

    /** {@code !x} ou {@code -x}. */
    public record Unary(Operator operator, Expression operand) implements Expression { }

    /** {@code condition ? siVrai : siFaux}. */
    public record Conditional(Expression condition, Expression whenTrue, Expression whenFalse) implements Expression { }

    /** Liste {@code [1, 2, 3]}. */
    public record ListLiteral(List<Expression> elements) implements Expression {
        public ListLiteral {
            elements = List.copyOf(elements);
        }
    }

    /** {@code now} : instant de l'évaluation. */
    public record Now() implements Expression { }

    /** Opérateurs des expressions, avec leur symbole. */
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

    /** Redirection d'un flux vers un fichier. */
    public record Redirect(Token.Stream stream, boolean append, Argument target) { }
}
