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
    private final ParseContext context;
    private int pos;

    private Parser(List<Token> tokens, ParseContext context) {
        this.tokens = tokens;
        this.context = context;
    }

    public static Ast.Script parse(String line) {
        return parse(line, ParseContext.NONE);
    }

    /**
     * @param staticNames recognizes qualified names designating a Java class or static field
     *                    ({@code Math.PI}), expressions at the start of a statement (FR-46)
     */
    public static Ast.Script parse(String line, Predicate<String> staticNames) {
        return parse(line, ParseContext.of(staticNames));
    }

    /** The line, read with what the context knows (Java names, short-form commands). */
    public static Ast.Script parse(String line, ParseContext context) {
        return new Parser(Lexer.tokenize(line, context), context).script();
    }

    /** Pipeline alone (content of a group {@code ( … )}). */
    static Ast.Pipeline pipeline(String text, ParseContext context) {
        var parser = new Parser(Lexer.tokenize(text, context), context);
        if (parser.atEnd()) {
            throw new SyntaxException(Messages.get("syntax.emptyParentheses"));
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
                throw new SyntaxException(Messages.get("syntax.noCommandBefore", symbol(c)));
            }
            if (peek() instanceof Token.Background) {
                throw new SyntaxException(Messages.get("syntax.noCommandBefore", "&"));
            }
            steps.add(new Step(connector, statement()));
            if (atEnd()) {
                break;
            }
            if (peek() instanceof Token.Background) {
                pos++; // already marked on the statement; the next one follows at once, like after ";"
                connector = Connector.ALWAYS;
                continue;
            }
            if (!(next() instanceof Token.Separator(var c))) {
                throw new IllegalStateException("separator expected");
            }
            connector = c;
            if (atEnd() && c != Connector.ALWAYS) {
                throw new SyntaxException(Messages.get("syntax.commandExpectedAfter", symbol(c)));
            }
        }
        return new Ast.Script(steps);
    }

    private Statement statement() {
        Optional<String> assignTo = Optional.empty();
        if (peek() instanceof Token.AssignTo(var name)) {
            pos++;
            assignTo = Optional.of(name);
            if (atEnd() || peek() instanceof Token.Separator || peek() instanceof Token.Background) {
                throw new SyntaxException(Messages.get("syntax.valueExpectedAfterAssignment", name));
            }
        }
        Ast.Pipeline pipeline = pipeline();
        boolean background = !atEnd() && peek() instanceof Token.Background;
        if (background && assignTo.isPresent()) {
            throw new SyntaxException(Messages.get("syntax.backgroundAssignment", assignTo.get()));
        }
        if (!atEnd() && !(peek() instanceof Token.Separator) && !background) {
            throw unexpected(peek());
        }
        return new Statement(assignTo, pipeline, background);
    }

    /** Stages separated by {@code |}, each followed by its redirections. */
    private Ast.Pipeline pipeline() {
        List<Ast.Stage> stages = new ArrayList<>();
        List<Redirect> redirects = new ArrayList<>();
        while (true) {
            if (atEnd() || peek() instanceof Token.Separator || peek() instanceof Token.Pipe
                    || peek() instanceof Token.Background) {
                throw new SyntaxException(stages.isEmpty() ? Messages.get("syntax.commandExpected")
                        : Messages.get("syntax.commandExpectedAfter", "|"));
            }
            Body body = body();
            if (!stages.isEmpty() && body instanceof Ast.ExpressionBody(var expression)) {
                if (expression instanceof Ast.BlockExpression || expression instanceof Ast.Lambda
                        || expression instanceof Ast.MethodRef) {
                    throw new SyntaxException(Messages.get("syntax.blockAsStage"));
                }
                throw new SyntaxException(Messages.get("syntax.valueNotFirstStage"));
            }
            boolean errorsToOutput = false;
            while (!atEnd() && peek() instanceof Token.Redirection(var stream, var append)) {
                pos++;
                if (stream == RedirectedStream.ERR_TO_OUT) {
                    errorsToOutput = true;
                    continue;
                }
                if (atEnd() || !isArgument(peek())) {
                    throw new SyntaxException(Messages.get("syntax.redirectionFileExpected"));
                }
                if (stream == RedirectedStream.OUT && peekAt(1) instanceof Token.Pipe) {
                    throw new SyntaxException(Messages.get("syntax.redirectionNotLast"));
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
                    throw new SyntaxException(Messages.get("syntax.commandNameExpected"));
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
     * the comparison operator, not a redirection, for a command whose cmdlet declares it
     * ({@code @CmdletInfo(comparison = true)}).
     */
    private boolean isShortFormOperator(String command, boolean forceNative, List<Argument> arguments) {
        return !forceNative && context.acceptsComparison(command) && arguments.size() == 1
                && arguments.getFirst() instanceof Ast.WordArgument
                && peek() instanceof Token.Redirection(var stream, var append)
                && stream == RedirectedStream.OUT && !append;
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
        return new SyntaxException(token instanceof Token.Expr ? Messages.get("syntax.unexpectedExpression")
                : Messages.get("syntax.unexpected", describe(token)));
    }

    private static String describe(Token token) {
        return switch (token) {
            case Token.Word(var text) -> text;
            case Token.Expr _ -> "expression"; // not displayed: see unexpected
            case Token.AssignTo(var name) -> "$" + name + " =";
            case Token.Separator(var c) -> symbol(c);
            case Token.Pipe _ -> "|";
            case Token.Background _ -> "&";
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
