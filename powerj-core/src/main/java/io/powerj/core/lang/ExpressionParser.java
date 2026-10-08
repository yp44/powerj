package io.powerj.core.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.powerj.core.lang.Ast.Expression;
import io.powerj.core.lang.Ast.Operator;

/**
 * Parses expressions with Java syntax (specification FR-32, FR-33, §3.13): content of a block
 * {@code { … }}, and expressions placed in a command line ({@code Math.max(3, 7)},
 * {@code $l.size()}, {@code new File("x")}, {@code (ls).name}).
 * <p>
 * Precedence, from lowest to highest: ternary, {@code ||}, {@code &&}, equality, comparison,
 * addition, multiplication, unary and {@code [type]} conversion, access ({@code .nom}, {@code .méthode(…)},
 * {@code [i]}).
 * <p>
 * In a command line, outside parentheses, an expression stops where the command syntax resumes:
 * {@code |}, {@code ;}, {@code &&}, {@code ||}, {@code >} (redirection), or a word that is not
 * an operator. Accesses are written attached there ({@code $l.size()}, not {@code $l .size()}).
 */
public final class ExpressionParser {

    /** Context of the expression. */
    public enum Mode {
        /** Content of a block or of parentheses: full grammar. */
        BLOCK,
        /** Start of statement: operators allowed, except those that have a command meaning. */
        STATEMENT,
        /** Command argument: a value and its accesses, without operators. */
        ARGUMENT
    }

    /** Lexical element of an expression; {@code at} and {@code end} delimit its text. */
    private sealed interface Tok {
        int at();

        int end();
    }

    private record Value(Object value, int at, int end) implements Tok { }

    private record Text(List<StringPart> parts, int at, int end) implements Tok { }

    private record Ident(String name, int at, int end) implements Tok { }

    private record Variable(String name, int at, int end) implements Tok { }

    private record Symbol(String text, int at, int end) implements Tok { }

    /** Symbols, longest first. */
    private static final List<String> SYMBOLS = List.of("->", "::", "==", "!=", "<=", ">=", "&&", "||",
            "<", ">", "+", "-", "*", "/", "%", "!", "?", ":", "(", ")", "[", "]", "{", "}", ",", ".");

    /** Operators that, outside parentheses in a command line, belong to the command syntax. */
    private static final Set<String> COMMAND_OPERATORS = Set.of("&&", "||", ">", ">=");

    private static final Set<String> PRIMITIVES = Set.of("boolean", "byte", "char", "short", "int", "long",
            "float", "double");

    private final String input;
    private final Mode mode;
    private Predicate<String> staticNames;
    private int pos;
    private int depth;
    private Tok lookahead;

    private ExpressionParser(String input, int start, Mode mode, Predicate<String> staticNames) {
        this.input = input;
        this.pos = start;
        this.mode = mode;
        this.staticNames = staticNames;
    }

    /** Parses the complete content of a block. */
    public static Expression parse(String source) {
        return parse(source, _ -> false);
    }

    /**
     * Parses the complete content of a block.
     *
     * @param staticNames recognizes qualified names designating a class or a static field
     *                    ({@code Math.PI}), for commands placed in parentheses
     */
    public static Expression parse(String source, Predicate<String> staticNames) {
        var parser = new ExpressionParser(source, 0, Mode.BLOCK, staticNames);
        if (parser.atEnd()) {
            throw new SyntaxException("bloc vide { }");
        }
        Expression expression = parser.expression();
        if (!parser.atEnd()) {
            throw parser.unexpected(parser.peek());
        }
        return expression;
    }

    /** Parsed block {@code { source }}: lambda ({@code f -> …}) or {@code $_} block. */
    public static Expression block(String source) {
        return function(source, _ -> false);
    }

    /** Content of a block: lambda if the text starts with parameters and {@code ->}, otherwise {@code $_} block. */
    public static Expression function(String source, Predicate<String> staticNames) {
        Matcher header = LAMBDA_HEADER.matcher(source);
        if (header.lookingAt()) {
            List<String> parameters = parameters(header);
            String body = source.substring(header.end());
            if (body.isBlank()) {
                throw new SyntaxException("corps de lambda attendu après « -> »");
            }
            return new Ast.Lambda(source.strip(), parameters, parse(body, withParameters(staticNames, parameters)));
        }
        return new Ast.BlockExpression(source.strip(), parse(source, staticNames));
    }

    /**
     * Names known at the start of a parenthesized command: Java static names, plus the parameters of
     * enclosing lambdas ({@code (f.size)} in {@code f -> …} is an expression, not the command
     * {@code f.size}).
     */
    private record Names(Predicate<String> statics, Set<String> parameters) implements Predicate<String> {
        @Override
        public boolean test(String name) {
            int dot = name.indexOf('.');
            return parameters.contains(dot < 0 ? name : name.substring(0, dot)) || statics.test(name);
        }
    }

    private static Predicate<String> withParameters(Predicate<String> staticNames, List<String> parameters) {
        if (parameters.isEmpty()) {
            return staticNames;
        }
        Set<String> names = new java.util.HashSet<>(parameters);
        Predicate<String> statics = staticNames;
        if (staticNames instanceof Names(var outer, var outerParameters)) {
            names.addAll(outerParameters);
            statics = outer;
        }
        return new Names(statics, Set.copyOf(names));
    }

    /**
     * Lambda header ({@code f ->}, {@code (a, b) ->}) starting at {@code at}: its parameters and the
     * position following {@code ->}.
     */
    public static Optional<Map.Entry<List<String>, Integer>> lambdaHeader(String input, int at) {
        Matcher header = LAMBDA_HEADER.matcher(input).region(at, input.length());
        if (!header.lookingAt()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Map.entry(parameters(header), header.end()));
        } catch (SyntaxException e) {
            return Optional.empty();
        }
    }

    /** Is {@code name} a parameter of an enclosing lambda? */
    static boolean isParameter(Predicate<String> staticNames, String name) {
        return staticNames instanceof Names(var _, var parameters) && parameters.contains(name);
    }

    /** {@code f ->}, {@code (a, b) ->}, {@code () ->} at the start of the text. */
    private static final Pattern LAMBDA_HEADER = Pattern.compile(
            "\\s*(?:([\\p{L}_][\\p{L}\\p{N}_]*)|\\(\\s*([\\p{L}_][\\p{L}\\p{N}_]*(?:\\s*,\\s*[\\p{L}_][\\p{L}\\p{N}_]*)*)?\\s*\\))\\s*->");

    private static List<String> parameters(Matcher header) {
        if (header.group(1) != null) {
            return List.of(header.group(1));
        }
        if (header.group(2) == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (String name : header.group(2).split(",")) {
            String trimmed = name.strip();
            if (names.contains(trimmed)) {
                throw new SyntaxException("paramètre de lambda en double : " + trimmed);
            }
            names.add(trimmed);
        }
        return names;
    }

    /**
     * Parses the expression starting at {@code start} in a command line, and indicates where it
     * stops.
     */
    static Lexer.Scanned<Expression> scan(String input, int start, Mode mode, Predicate<String> staticNames) {
        var parser = new ExpressionParser(input, start, mode, staticNames);
        Expression expression = mode == Mode.ARGUMENT ? parser.postfix(parser.primary()) : parser.expression();
        return new Lexer.Scanned<>(expression, parser.pos);
    }

    /** Group {@code ( … )} starting at {@code open}: expression or pipeline; used for {@code $( … )}. */
    static Lexer.Scanned<Expression> group(String input, int open, Predicate<String> staticNames) {
        var parser = new ExpressionParser(input, open, Mode.BLOCK, staticNames);
        Tok paren = parser.next();
        Expression expression = parser.group(paren);
        return new Lexer.Scanned<>(expression, parser.pos);
    }

    // --- Grammaire ---

    private Expression expression() {
        Expression condition = or();
        if (accept("?")) {
            depth++;
            Expression whenTrue = expression();
            expect(":");
            depth--;
            Expression whenFalse = expression();
            return new Ast.Conditional(condition, whenTrue, whenFalse);
        }
        return condition;
    }

    private Expression or() {
        Expression left = and();
        while (accept("||")) {
            left = new Ast.Binary(Operator.OR, left, and());
        }
        return left;
    }

    private Expression and() {
        Expression left = equality();
        while (accept("&&")) {
            left = new Ast.Binary(Operator.AND, left, equality());
        }
        return left;
    }

    private Expression equality() {
        Expression left = relational();
        while (true) {
            if (accept("==")) {
                left = new Ast.Binary(Operator.EQ, left, relational());
            } else if (accept("!=")) {
                left = new Ast.Binary(Operator.NE, left, relational());
            } else {
                return left;
            }
        }
    }

    private Expression relational() {
        Expression left = additive();
        while (true) {
            Operator op = accept("<") ? Operator.LT : accept("<=") ? Operator.LE
                    : accept(">") ? Operator.GT : accept(">=") ? Operator.GE : null;
            if (op == null) {
                return left;
            }
            left = new Ast.Binary(op, left, additive());
        }
    }

    private Expression additive() {
        Expression left = multiplicative();
        while (true) {
            Operator op = accept("+") ? Operator.ADD : accept("-") ? Operator.SUB : null;
            if (op == null) {
                return left;
            }
            left = new Ast.Binary(op, left, multiplicative());
        }
    }

    private Expression multiplicative() {
        Expression left = unary();
        while (true) {
            Operator op = accept("*") ? Operator.MUL : accept("/") ? Operator.DIV : accept("%") ? Operator.REM : null;
            if (op == null) {
                return left;
            }
            left = new Ast.Binary(op, left, unary());
        }
    }

    private Expression unary() {
        if (accept("!")) {
            return new Ast.Unary(Operator.NOT, unary());
        }
        if (accept("-")) {
            return new Ast.Unary(Operator.NEG, unary());
        }
        if (accept("+")) {
            return unary();
        }
        return postfix(primary());
    }

    private Expression postfix(Expression target) {
        Expression current = target;
        while (!atEnd()) {
            Tok token = peek();
            if (restricted() && token.at() != pos) {
                return current; // dans une ligne de commande, un espace termine l'expression
            }
            if (isSymbol(token, ".")) {
                next();
                if (atEnd() || !(peek() instanceof Ident(var name, var at, _)) || restricted() && at != pos) {
                    throw new SyntaxException("nom attendu après « . »" + where());
                }
                next();
                if (!atEnd() && isSymbol(peek(), "(") && peek().at() == pos) {
                    next();
                    current = new Ast.Invoke(current, name, callArguments());
                } else {
                    current = new Ast.Get(current, name);
                }
            } else if (isSpread(token)) {
                next(); // *
                next(); // .
                if (atEnd() || !(peek() instanceof Ident(var name, var at, _)) || at != pos) {
                    throw new SyntaxException("nom attendu après « *. »" + where());
                }
                next();
                if (!atEnd() && isSymbol(peek(), "(") && peek().at() == pos) {
                    next();
                    current = new Ast.SpreadInvoke(current, name, callArguments());
                } else {
                    current = new Ast.SpreadGet(current, name);
                }
            } else if (isSymbol(token, "::")) {
                next();
                if (atEnd() || !(peek() instanceof Ident(var name, var at, _)) || restricted() && at != pos) {
                    throw new SyntaxException("nom de méthode attendu après « :: »" + where());
                }
                next();
                current = new Ast.MethodRef(current, name);
            } else if (isSymbol(token, "[")) {
                next();
                depth++;
                Expression index = expression();
                expect("]");
                depth--;
                current = new Ast.At(current, index);
            } else {
                return current;
            }
        }
        return current;
    }

    /**
     * Arguments of a Java call, up to {@code )}: expressions or lambdas without braces
     * ({@code s -> s.length()}, {@code (a, b) -> a - b}).
     */
    private List<Expression> callArguments() {
        depth++;
        List<Expression> arguments = new ArrayList<>();
        if (!accept(")")) {
            do {
                arguments.add(lambdaOrExpression());
            } while (accept(","));
            expect(")");
        }
        depth--;
        return arguments;
    }

    private Expression lambdaOrExpression() {
        int start = skipBlanks(input, pos);
        Matcher header = LAMBDA_HEADER.matcher(input).region(start, input.length());
        if (!header.lookingAt()) {
            return expression();
        }
        List<String> parameters = parameters(header);
        reset(header.end());
        Predicate<String> enclosing = staticNames;
        staticNames = withParameters(enclosing, parameters);
        Expression body;
        try {
            body = expression();
        } finally {
            staticNames = enclosing;
        }
        return new Ast.Lambda(input.substring(start, pos).strip(), parameters, body);
    }

    /** Comma-separated arguments up to the closing symbol (the opening symbol has been consumed). */
    private List<Expression> arguments(String close) {
        depth++;
        List<Expression> arguments = new ArrayList<>();
        if (!accept(close)) {
            do {
                arguments.add(expression());
            } while (accept(","));
            expect(close);
        }
        depth--;
        return arguments;
    }

    private Expression primary() {
        if (atEnd()) {
            throw new SyntaxException("expression incomplète" + context());
        }
        Tok token = next();
        return switch (token) {
            case Value(var value, _, _) -> new Ast.Literal(value);
            case Text(var parts, _, _) -> new Ast.StringExpression(parts);
            case Variable(var name, _, _) -> new Ast.VariableExpression(name, List.of());
            case Ident(var name, _, _) when name.equals("true") -> new Ast.Literal(Boolean.TRUE);
            case Ident(var name, _, _) when name.equals("false") -> new Ast.Literal(Boolean.FALSE);
            case Ident(var name, _, _) when name.equals("null") -> new Ast.Literal(null);
            case Ident(var name, _, _) when name.equals("now") -> new Ast.Now();
            case Ident(var name, _, _) when name.equals("new") -> instantiation();
            case Ident(var name, _, _) -> new Ast.Name(name);
            case Symbol(var text, _, _) when text.equals("(") -> group(token);
            case Symbol(var text, _, _) when text.equals("[") -> castOrList(token);
            case Symbol(var text, _, _) when text.equals("{") -> block(token);
            case Symbol _ -> throw unexpected(token);
        };
    }

    /** {@code new nom.Qualifie(arguments)}. */
    private Expression instantiation() {
        String type = qualifiedName("new");
        if (atEnd() || !isSymbol(peek(), "(")) {
            throw new SyntaxException("« ( » attendu après new " + type);
        }
        next();
        return new Ast.New(type, callArguments());
    }

    private String qualifiedName(String after) {
        if (atEnd() || !(peek() instanceof Ident(var first, _, _))) {
            throw new SyntaxException("nom de classe attendu après " + after + where());
        }
        next();
        var name = new StringBuilder(first);
        while (!atEnd() && isSymbol(peek(), ".") && peek().at() == pos) {
            next();
            if (atEnd() || !(peek() instanceof Ident(var part, _, _))) {
                throw new SyntaxException("nom attendu après « . »" + where());
            }
            next();
            name.append('.').append(part);
        }
        return name.toString();
    }

    /**
     * {@code ( … )}: an expression ({@code (1 + 2)}, {@code (Math.max(1, 2))}) or a command pipeline
     * ({@code (ls)}, {@code (ls -r | where size > 1mb)}), as at the start of a line (FR-46).
     */
    private Expression group(Tok paren) {
        int open = paren.at();
        int close = matching(input, open);
        if (close < 0) {
            throw new SyntaxException("« ) » manquante (ouverte à la position " + (open + 1) + ")");
        }
        int contentStart = skipBlanks(input, open + 1);
        if (contentStart == close) {
            throw new SyntaxException("parenthèses vides");
        }
        if (Lexer.expressionAt(input, contentStart, staticNames)) {
            depth++;
            Expression inner = expression();
            depth--;
            if (!atEnd() && isSymbol(peek(), ")") && peek().at() == close) {
                next();
                return inner;
            }
            // valeur suivie d'un pipeline : ($l | where { … })
        }
        Ast.Pipeline pipeline = Parser.pipeline(input.substring(open + 1, close), staticNames);
        reset(close + 1);
        return new Ast.SubExpression(pipeline);
    }

    /** {@code [type] valeur} (conversion) or {@code [1, 2, 3]} (list). */
    private Expression castOrList(Tok bracket) {
        int start = skipBlanks(input, bracket.at() + 1);
        int end = start;
        while (end < input.length() && (Character.isJavaIdentifierPart(input.charAt(end)) || input.charAt(end) == '.')) {
            end++;
        }
        int closing = skipBlanks(input, end);
        if (end > start && Character.isJavaIdentifierStart(input.charAt(start))
                && closing < input.length() && input.charAt(closing) == ']') {
            String type = input.substring(start, end);
            int after = skipBlanks(input, closing + 1);
            if (isOperandStart(after) && (PRIMITIVES.contains(type) || Character.isUpperCase(lastPart(type).charAt(0)))) {
                reset(closing + 1);
                return new Ast.Cast(type, unary());
            }
        }
        return new Ast.ListLiteral(arguments("]"));
    }

    private boolean isOperandStart(int at) {
        if (at >= input.length()) {
            return false;
        }
        char c = input.charAt(at);
        return c == '$' || c == '"' || c == '\'' || c == '(' || c == '[' || c == '{' || c == '!' || c == '-'
                || Character.isLetterOrDigit(c) || c == '_';
    }

    private static String lastPart(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    /** {@code { … }}: block evaluated later ({@code ScriptBlock}). */
    private Expression block(Tok brace) {
        int close = matching(input, brace.at());
        if (close < 0) {
            throw new SyntaxException("bloc non fermé (« { » à la position " + (brace.at() + 1) + ")");
        }
        String source = input.substring(brace.at() + 1, close);
        reset(close + 1);
        return function(source, staticNames);
    }

    // --- Outils ---

    /** Outside parentheses in a command line. */
    private boolean restricted() {
        return depth == 0 && mode != Mode.BLOCK;
    }

    private boolean accept(String symbol) {
        if (atEnd() || !isSymbol(peek(), symbol)) {
            return false;
        }
        if (restricted() && (mode == Mode.ARGUMENT || COMMAND_OPERATORS.contains(symbol))) {
            return false; // syntaxe des commandes : redirection, enchaînement
        }
        next();
        return true;
    }

    private void expect(String symbol) {
        if (atEnd() || !isSymbol(peek(), symbol)) {
            throw atEnd() ? new SyntaxException("« " + symbol + " » manquant" + context())
                    : new SyntaxException("« " + symbol + " » attendu" + where());
        }
        next();
    }

    /** {@code *.} attached to a name: "spread" operator ({@code $f*.name}), not a multiplication. */
    private boolean isSpread(Tok token) {
        int at = token.at();
        return isSymbol(token, "*") && at + 2 < input.length() && input.charAt(at + 1) == '.'
                && Character.isJavaIdentifierStart(input.charAt(at + 2));
    }

    private static boolean isSymbol(Tok token, String symbol) {
        return token instanceof Symbol(var text, _, _) && text.equals(symbol);
    }

    private SyntaxException unexpected(Tok token) {
        if (isSymbol(token, "=")) {
            return new SyntaxException("« = » dans une expression : pour comparer, utiliser == (position "
                    + (token.at() + 1) + ")");
        }
        String text = input.substring(token.at(), token.end());
        return new SyntaxException("« " + text + " » inattendu (position " + (token.at() + 1) + ")");
    }

    private String where() {
        return atEnd() ? " en fin d'expression" : " à la position " + (peek().at() + 1);
    }

    private String context() {
        return mode == Mode.BLOCK ? " dans { " + input.strip() + " }" : "";
    }

    private void reset(int position) {
        pos = position;
        lookahead = null;
    }

    private boolean atEnd() {
        return peek() == null;
    }

    private Tok peek() {
        if (lookahead == null) {
            lookahead = scanToken(skipBlanks(input, pos));
        }
        return lookahead;
    }

    private Tok next() {
        Tok token = peek();
        pos = token.end();
        lookahead = null;
        return token;
    }

    static int skipBlanks(String input, int from) {
        int at = from;
        while (at < input.length() && Lexer.isBlank(input.charAt(at))) {
            at++;
        }
        return at;
    }

    // --- Découpage ---

    private Tok scanToken(int start) {
        if (start >= input.length()) {
            return null;
        }
        char c = input.charAt(start);
        if (c == '"') {
            var scanned = Lexer.stringAt(input, start, staticNames);
            return new Text(scanned.value(), start, scanned.end());
        }
        if (c == '\'') {
            var scanned = character(start);
            return new Value(scanned.value(), start, scanned.end());
        }
        if (c == '$') {
            return variable(start);
        }
        if (Character.isDigit(c)) {
            return number(start);
        }
        if (Character.isJavaIdentifierStart(c)) {
            int end = start;
            while (end < input.length() && Character.isJavaIdentifierPart(input.charAt(end))) {
                end++;
            }
            return new Ident(input.substring(start, end), start, end);
        }
        for (String symbol : SYMBOLS) {
            if (input.startsWith(symbol, start)) {
                return new Symbol(symbol, start, start + symbol.length());
            }
        }
        // Caractère hors expression (|, ;, =…) : selon le contexte, fin de l'expression ou erreur.
        return new Symbol(String.valueOf(c), start, start + 1);
    }

    private Tok variable(int dollar) {
        int start = dollar + 1;
        int end = start;
        if (end < input.length() && (input.charAt(end) == '_' || input.charAt(end) == '?')
                && (end + 1 >= input.length() || !Character.isLetterOrDigit(input.charAt(end + 1)))) {
            return new Variable(input.substring(start, end + 1), dollar, end + 1); // $_ et $?
        }
        while (end < input.length() && (Character.isLetterOrDigit(input.charAt(end)) || input.charAt(end) == '_')) {
            end++;
        }
        if (end == start) {
            throw new SyntaxException("nom de variable attendu après $ (position " + (dollar + 1) + ")");
        }
        return new Variable(input.substring(start, end), dollar, end);
    }

    /** Integer, decimal, {@code L} suffix, or unit literal ({@code 10kb}, {@code 7d}). */
    private Tok number(int start) {
        int end = start;
        while (end < input.length() && Character.isDigit(input.charAt(end))) {
            end++;
        }
        if (end + 1 < input.length() && input.charAt(end) == '.' && Character.isDigit(input.charAt(end + 1))) {
            end++;
            while (end < input.length() && Character.isDigit(input.charAt(end))) {
                end++;
            }
        }
        String digits = input.substring(start, end);
        int suffixStart = end;
        while (end < input.length() && Character.isLetter(input.charAt(end))) {
            end++;
        }
        String suffix = input.substring(suffixStart, end);
        try {
            if (suffix.isEmpty()) {
                if (digits.contains(".")) {
                    return new Value(Double.valueOf(digits), start, end);
                }
                long value = Long.parseLong(digits);
                return new Value(value == (int) value ? (Object) (int) value : (Object) value, start, end);
            }
            if (suffix.equals("L") && !digits.contains(".")) {
                return new Value(Long.valueOf(digits), start, end);
            }
        } catch (NumberFormatException _) {
            throw new SyntaxException("nombre trop grand : " + digits);
        }
        Object value = Units.parse(digits + suffix).orElseThrow(() -> new SyntaxException("nombre invalide : "
                + digits + suffix + " (unités : b kb mb gb tb, ms s m h d)"));
        return new Value(value, start, end);
    }

    private Lexer.Scanned<Character> character(int open) {
        int at = open + 1;
        if (at >= input.length()) {
            throw new SyntaxException("caractère non fermé (position " + (open + 1) + ")");
        }
        char value = input.charAt(at++);
        if (value == '\\') {
            if (at >= input.length()) {
                throw new SyntaxException("caractère non fermé (position " + (open + 1) + ")");
            }
            char e = input.charAt(at++);
            value = switch (e) {
                case '\\' -> '\\';
                case '\'' -> '\'';
                case '"' -> '"';
                case 'n' -> '\n';
                case 't' -> '\t';
                case 'r' -> '\r';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case '0' -> '\0';
                case 'u' -> {
                    if (at + 4 > input.length()) {
                        throw new SyntaxException("échappement \\u incomplet");
                    }
                    try {
                        char u = (char) Integer.parseInt(input.substring(at, at + 4), 16);
                        at += 4;
                        yield u;
                    } catch (NumberFormatException _) {
                        throw new SyntaxException("échappement \\u" + input.substring(at, at + 4) + " invalide");
                    }
                }
                default -> throw new SyntaxException("échappement inconnu \\" + e);
            };
        } else if (value == '\'') {
            throw new SyntaxException("caractère vide '' (position " + (open + 1) + ")");
        }
        if (at >= input.length() || input.charAt(at) != '\'') {
            throw new SyntaxException("un caractère s'écrit entre apostrophes : 'a' ; pour une chaîne, utiliser \"…\""
                    + " (position " + (open + 1) + ")");
        }
        return new Lexer.Scanned<>(value, at + 1);
    }

    /**
     * Position of the closing symbol matching the one opened at {@code open} ({@code (}, {@code [} or
     * {@code {}), ignoring the content of strings and characters; -1 if it is missing.
     */
    static int matching(String input, int open) {
        List<Character> expected = new ArrayList<>();
        int at = open;
        while (at < input.length()) {
            char c = input.charAt(at);
            switch (c) {
                case '(' -> expected.add(')');
                case '[' -> expected.add(']');
                case '{' -> expected.add('}');
                case ')', ']', '}' -> {
                    if (!expected.isEmpty() && expected.getLast() == c) {
                        expected.removeLast();
                        if (expected.isEmpty()) {
                            return at;
                        }
                    }
                }
                case '"' -> {
                    at = skipString(input, at);
                    if (at < 0) {
                        return -1;
                    }
                    continue;
                }
                case '\'' -> {
                    int end = characterEnd(input, at);
                    if (end > 0) {
                        at = end;
                        continue;
                    }
                }
                default -> { }
            }
            at++;
        }
        return -1;
    }

    /** Position following the string opened at {@code open}; -1 if it is not closed. */
    private static int skipString(String input, int open) {
        int at = open + 1;
        while (at < input.length()) {
            char c = input.charAt(at);
            if (c == '\\') {
                at += 2;
            } else if (c == '"') {
                return at + 1;
            } else if (c == '$' && at + 1 < input.length() && input.charAt(at + 1) == '(') {
                int close = matching(input, at + 1);
                if (close < 0) {
                    return -1;
                }
                at = close + 1;
            } else {
                at++;
            }
        }
        return -1;
    }

    /** End of a character literal ({@code 'a'}, {@code '\n'}, {@code 'é'}) at {@code open}, otherwise -1. */
    private static int characterEnd(String input, int open) {
        if (open + 2 < input.length() && input.charAt(open + 1) != '\\' && input.charAt(open + 2) == '\'') {
            return open + 3;
        }
        if (open + 3 < input.length() && input.charAt(open + 1) == '\\' && input.charAt(open + 3) == '\'') {
            return open + 4;
        }
        if (open + 7 < input.length() && input.startsWith("\\u", open + 1) && input.charAt(open + 7) == '\'') {
            return open + 8;
        }
        return -1;
    }
}
