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
     * Instruction : affectation éventuelle, corps, redirections.
     *
     * @param assignTo variable affectée ({@code $x = …}), si présente
     */
    public record Statement(Optional<String> assignTo, Body body, List<Redirect> redirects) {
        public Statement {
            redirects = List.copyOf(redirects);
        }
    }

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

    /** Expression (limitée à l'étape 2 : littéraux, chaînes, variables). */
    public sealed interface Expression { }

    public record Literal(Object value) implements Expression { }

    public record StringExpression(List<StringPart> parts) implements Expression { }

    public record VariableExpression(String name, List<Accessor> accessors) implements Expression { }

    /** Redirection d'un flux vers un fichier. */
    public record Redirect(Token.Stream stream, boolean append, Argument target) { }
}
