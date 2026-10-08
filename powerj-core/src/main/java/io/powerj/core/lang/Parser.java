package io.powerj.core.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import io.powerj.core.lang.Ast.Argument;
import io.powerj.core.lang.Ast.Body;
import io.powerj.core.lang.Ast.Redirect;
import io.powerj.core.lang.Ast.Statement;
import io.powerj.core.lang.Ast.Step;

/** Builds the {@link Ast} of a line from its {@link Token}s. */
public final class Parser {

    private final List<Token> tokens;
    private int pos;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static Ast.Script parse(String line) {
        return parse(line, _ -> false);
    }

    /**
     * @param staticNames recognizes qualified names designating a Java class or static field
     *                    ({@code Math.PI}), expressions at the start of a statement (FR-46)
     */
    public static Ast.Script parse(String line, Predicate<String> staticNames) {
        return new Parser(Lexer.tokenize(line, staticNames)).script();
    }

    /** Pipeline alone (content of a group {@code ( … )}). */
    static Ast.Pipeline pipeline(String text, Predicate<String> staticNames) {
        var parser = new Parser(Lexer.tokenize(text, staticNames));
        if (parser.atEnd()) {
            throw new SyntaxException("parenthèses vides");
        }
        Ast.Pipeline pipeline = parser.pipeline();
        if (!parser.atEnd()) {
            throw parser.unexpected(parser.peek());
        }
        return pipeline;
    }

    private Ast.Script script() {
        List<Step> steps = new ArrayList<>();
        var connector = Connector.ALWAYS;
        while (!atEnd()) {
            if (peek() instanceof Token.Separator(var c)) {
                if (c == Connector.ALWAYS) {
                    pos++; // superfluous ";"
                    continue;
                }
                throw new SyntaxException("« " + symbol(c) + " » sans commande avant");
            }
            steps.add(new Step(connector, statement()));
            if (atEnd()) {
                break;
            }
            if (!(next() instanceof Token.Separator(var c))) {
                throw new IllegalStateException("séparateur attendu");
            }
            connector = c;
            if (atEnd() && c != Connector.ALWAYS) {
                throw new SyntaxException("commande attendue après « " + symbol(c) + " »");
            }
        }
        return new Ast.Script(steps);
    }

    private Statement statement() {
        Optional<String> assignTo = Optional.empty();
        if (peek() instanceof Token.AssignTo(var name)) {
            pos++;
            assignTo = Optional.of(name);
            if (atEnd() || peek() instanceof Token.Separator) {
                throw new SyntaxException("valeur attendue après « $" + name + " = »");
            }
        }
        Ast.Pipeline pipeline = pipeline();
        if (!atEnd() && !(peek() instanceof Token.Separator)) {
            throw unexpected(peek());
        }
        return new Statement(assignTo, pipeline);
    }

    /** Stages separated by {@code |}, each followed by its redirections. */
    private Ast.Pipeline pipeline() {
        List<Ast.Stage> stages = new ArrayList<>();
        List<Redirect> redirects = new ArrayList<>();
        while (true) {
            if (atEnd() || peek() instanceof Token.Separator || peek() instanceof Token.Pipe) {
                throw new SyntaxException(stages.isEmpty() ? "commande attendue" : "commande attendue après « | »");
            }
            Body body = body();
            if (!stages.isEmpty() && body instanceof Ast.ExpressionBody(var expression)) {
                if (expression instanceof Ast.BlockExpression || expression instanceof Ast.Lambda
                        || expression instanceof Ast.MethodRef) {
                    throw new SyntaxException("un bloc seul n'est pas une étape de pipeline : écrire map { … } pour"
                            + " transformer chaque objet, ou where { … } pour le filtrer");
                }
                throw new SyntaxException("une valeur ne peut être que la première étape d'un pipeline");
            }
            boolean errorsToOutput = false;
            while (!atEnd() && peek() instanceof Token.Redirection(var stream, var append)) {
                pos++;
                if (stream == Token.Stream.ERR_TO_OUT) {
                    errorsToOutput = true;
                    continue;
                }
                if (atEnd() || !isArgument(peek())) {
                    throw new SyntaxException("fichier attendu après la redirection");
                }
                if (stream == Token.Stream.OUT && peekAt(1) instanceof Token.Pipe) {
                    throw new SyntaxException("« > » redirige la sortie de tout le pipeline : le placer à la fin");
                }
                redirects.add(new Redirect(stream, append, argument(next())));
            }
            stages.add(new Ast.Stage(body, errorsToOutput));
            if (atEnd() || !(peek() instanceof Token.Pipe)) {
                return new Ast.Pipeline(stages, redirects);
            }
            pos++; // |
        }
    }

    private Body body() {
        Token first = next();
        return switch (first) {
            case Token.Expr(var expression) -> new Ast.ExpressionBody(expression);
            case Token.Word(var text) -> {
                boolean forceNative = text.startsWith("^");
                String name = forceNative ? text.substring(1) : text;
                if (name.isEmpty()) {
                    throw new SyntaxException("nom de commande attendu après ^");
                }
                List<Argument> arguments = new ArrayList<>();
                while (!atEnd()) {
                    if (isArgument(peek())) {
                        arguments.add(argument(next()));
                    } else if (isShortFormOperator(name, forceNative, arguments)) {
                        pos++;
                        arguments.add(new Ast.WordArgument(">"));
                    } else {
                        break;
                    }
                }
                yield new Ast.Command(name, forceNative, arguments);
            }
            default -> throw unexpected(first);
        };
    }

    /**
     * In the short form {@code where size > 1mb} (FR-36), the {@code >} following the property name is
     * the comparison operator, not a redirection.
     */
    private boolean isShortFormOperator(String command, boolean forceNative, List<Argument> arguments) {
        return !forceNative && command.equals("where") && arguments.size() == 1
                && arguments.getFirst() instanceof Ast.WordArgument
                && peek() instanceof Token.Redirection(var stream, var append)
                && stream == Token.Stream.OUT && !append;
    }

    private static boolean isArgument(Token token) {
        return token instanceof Token.Word || token instanceof Token.Expr;
    }

    private Argument argument(Token token) {
        return switch (token) {
            case Token.Word(var text) -> new Ast.WordArgument(text);
            case Token.Expr(var expression) -> new Ast.ExpressionArgument(expression);
            default -> throw unexpected(token);
        };
    }

    private SyntaxException unexpected(Token token) {
        return new SyntaxException("« " + describe(token) + " » inattendu");
    }

    private static String describe(Token token) {
        return switch (token) {
            case Token.Word(var text) -> text;
            case Token.Expr _ -> "expression";
            case Token.AssignTo(var name) -> "$" + name + " =";
            case Token.Separator(var c) -> symbol(c);
            case Token.Pipe _ -> "|";
            case Token.Redirection(var stream, var append) -> switch (stream) {
                case OUT -> append ? ">>" : ">";
                case ERR -> append ? "2>>" : "2>";
                case ERR_TO_OUT -> "2>&1";
            };
        };
    }

    private static String symbol(Connector connector) {
        return switch (connector) {
            case ALWAYS -> ";";
            case IF_SUCCESS -> "&&";
            case IF_FAILURE -> "||";
        };
    }

    private Token peek() {
        return tokens.get(pos);
    }

    private Token peekAt(int offset) {
        return pos + offset < tokens.size() ? tokens.get(pos + offset) : null;
    }

    private Token next() {
        return tokens.get(pos++);
    }

    private boolean atEnd() {
        return pos >= tokens.size();
    }
}
