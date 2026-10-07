package io.powerj.core.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.powerj.core.lang.Ast.Argument;
import io.powerj.core.lang.Ast.Body;
import io.powerj.core.lang.Ast.Expression;
import io.powerj.core.lang.Ast.Redirect;
import io.powerj.core.lang.Ast.Statement;
import io.powerj.core.lang.Ast.Step;

/** Construit l'{@link Ast} d'une ligne à partir de ses {@link Token}. */
public final class Parser {

    private final List<Token> tokens;
    private int pos;

    private Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static Ast.Script parse(String line) {
        return new Parser(Lexer.tokenize(line)).script();
    }

    private Ast.Script script() {
        List<Step> steps = new ArrayList<>();
        var connector = Connector.ALWAYS;
        while (!atEnd()) {
            if (peek() instanceof Token.Separator(var c)) {
                if (c == Connector.ALWAYS) {
                    pos++; // « ; » superflu
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
        if (peek() instanceof Token.Var(var name, var accessors) && peekAt(1) instanceof Token.Assign) {
            if (!accessors.isEmpty()) {
                throw new SyntaxException("affectation possible uniquement à une variable simple ($" + name + ")");
            }
            pos += 2;
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

    /** Étapes séparées par {@code |}, chacune suivie de ses redirections. */
    private Ast.Pipeline pipeline() {
        List<Ast.Stage> stages = new ArrayList<>();
        List<Redirect> redirects = new ArrayList<>();
        while (true) {
            if (atEnd() || peek() instanceof Token.Separator || peek() instanceof Token.Close
                    || peek() instanceof Token.Pipe) {
                throw new SyntaxException(stages.isEmpty() ? "commande attendue" : "commande attendue après « | »");
            }
            Body body = body();
            if (!stages.isEmpty() && body instanceof Ast.ExpressionBody) {
                throw new SyntaxException("une valeur ne peut être que la première étape d'un pipeline");
            }
            boolean errorsToOutput = false;
            while (!atEnd() && peek() instanceof Token.Redirection(var stream, var append)) {
                pos++;
                if (stream == Token.Stream.ERR_TO_OUT) {
                    errorsToOutput = true;
                    continue;
                }
                if (atEnd() || !isArgument(peek()) || peek() instanceof Token.Block) {
                    throw new SyntaxException("fichier attendu après la redirection");
                }
                if (stream == Token.Stream.OUT && !atEnd() && peekAt(1) instanceof Token.Pipe) {
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
        if (peek() instanceof Token.Open) {
            return new Ast.ExpressionBody(subExpression());
        }
        Token first = next();
        if (first instanceof Token.Word(var text) && !isLiteral(text)) {
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
            return new Ast.Command(name, forceNative, arguments);
        }
        Expression expression = switch (first) {
            case Token.Word(var text) -> new Ast.Literal(literal(text));
            case Token.Str(var parts) -> new Ast.StringExpression(parts);
            case Token.Var(var name, var accessors) -> new Ast.VariableExpression(name, accessors);
            case Token.Block(var source) -> ExpressionParser.block(source);
            default -> throw unexpected(first);
        };
        return new Ast.ExpressionBody(expression);
    }

    /**
     * Dans la forme courte {@code where size > 1mb} (FR-36), le {@code >} qui suit le nom de propriété est
     * l'opérateur de comparaison, pas une redirection.
     */
    private boolean isShortFormOperator(String command, boolean forceNative, List<Argument> arguments) {
        return !forceNative && command.equals("where") && arguments.size() == 1
                && arguments.getFirst() instanceof Ast.WordArgument
                && peek() instanceof Token.Redirection(var stream, var append)
                && stream == Token.Stream.OUT && !append;
    }

    private static boolean isArgument(Token token) {
        return token instanceof Token.Word || token instanceof Token.Str || token instanceof Token.Var
                || token instanceof Token.Open || token instanceof Token.Block;
    }

    /** {@code ( commande ou expression )} suivi de ses accès. */
    private Ast.SubExpression subExpression() {
        pos++; // (
        if (atEnd() || peek() instanceof Token.Close) {
            throw new SyntaxException("parenthèses vides");
        }
        Ast.Pipeline inner = pipeline();
        if (atEnd() || !(peek() instanceof Token.Close(var accessors))) {
            throw new SyntaxException(atEnd() ? "« ) » manquante" : "« ) » attendue avant « " + describe(peek()) + " »");
        }
        pos++;
        return new Ast.SubExpression(inner, accessors);
    }

    private Argument argument(Token token) {
        if (token instanceof Token.Open) {
            pos--;
            return new Ast.ExpressionArgument(subExpression());
        }
        return switch (token) {
            case Token.Word(var text) -> new Ast.WordArgument(text);
            case Token.Str(var parts) -> new Ast.ExpressionArgument(new Ast.StringExpression(parts));
            case Token.Var(var name, var accessors) ->
                    new Ast.ExpressionArgument(new Ast.VariableExpression(name, accessors));
            case Token.Block(var source) -> new Ast.ExpressionArgument(ExpressionParser.block(source));
            default -> throw unexpected(token);
        };
    }

    /** Mot qui, seul en tête d'instruction, est une valeur et non une commande. */
    private static boolean isLiteral(String text) {
        return text.equals("true") || text.equals("false") || text.equals("null")
                || text.matches("-?\\d+(\\.\\d+)?");
    }

    private static Object literal(String text) {
        return switch (text) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            case "null" -> null;
            case String n when n.contains(".") -> Double.valueOf(n);
            case String n -> {
                long value = Long.parseLong(n);
                yield value == (int) value ? (Object) (int) value : (Object) value;
            }
        };
    }

    private SyntaxException unexpected(Token token) {
        return new SyntaxException("« " + describe(token) + " » inattendu");
    }

    private static String describe(Token token) {
        return switch (token) {
            case Token.Word(var text) -> text;
            case Token.Str _ -> "chaîne";
            case Token.Var(var name, _) -> "$" + name;
            case Token.Assign _ -> "=";
            case Token.Separator(var c) -> symbol(c);
            case Token.Pipe _ -> "|";
            case Token.Redirection(var stream, var append) -> switch (stream) {
                case OUT -> append ? ">>" : ">";
                case ERR -> append ? "2>>" : "2>";
                case ERR_TO_OUT -> "2>&1";
            };
            case Token.Block _ -> "{";
            case Token.Open _ -> "(";
            case Token.Close _ -> ")";
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
