package io.powerj.core.lang;

import java.util.ArrayList;
import java.util.List;

import io.powerj.core.lang.Ast.Expression;
import io.powerj.core.lang.Ast.Operator;

/**
 * Analyse le contenu d'un bloc {@code { … }} : expression à la syntaxe Java (spécification FR-32, FR-33).
 * Priorités, de la plus faible à la plus forte : ternaire, {@code ||}, {@code &&}, égalité, comparaison,
 * addition, multiplication, unaire, accès ({@code .nom}, {@code .méthode(…)}, {@code [i]}).
 */
public final class ExpressionParser {

    /** Élément lexical d'une expression. */
    private sealed interface Tok {
        int at();
    }

    private record Value(Object value, int at) implements Tok { }

    private record Text(List<StringPart> parts, int at) implements Tok { }

    private record Ident(String name, int at) implements Tok { }

    private record Variable(String name, int at) implements Tok { }

    private record Symbol(String text, int at) implements Tok { }

    /** Symboles, les plus longs d'abord. */
    private static final List<String> SYMBOLS = List.of("==", "!=", "<=", ">=", "&&", "||",
            "<", ">", "+", "-", "*", "/", "%", "!", "?", ":", "(", ")", "[", "]", ",", ".");

    private final String source;
    private final List<Tok> tokens;
    private int pos;

    private ExpressionParser(String source) {
        this.source = source;
        this.tokens = new Scanner(source).run();
    }

    /** Analyse une expression complète. */
    public static Expression parse(String source) {
        var parser = new ExpressionParser(source);
        if (parser.tokens.isEmpty()) {
            throw new SyntaxException("bloc vide { }");
        }
        Expression expression = parser.expression();
        if (!parser.atEnd()) {
            throw parser.unexpected(parser.peek());
        }
        return expression;
    }

    /** Bloc {@code { source }} analysé. */
    public static Ast.BlockExpression block(String source) {
        return new Ast.BlockExpression(source.strip(), parse(source));
    }

    private Expression expression() {
        Expression condition = or();
        if (accept("?")) {
            Expression whenTrue = expression();
            expect(":");
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
        while (true) {
            if (accept(".")) {
                if (atEnd() || !(peek() instanceof Ident(var name, _))) {
                    throw new SyntaxException("nom attendu après « . »" + where());
                }
                pos++;
                if (accept("(")) {
                    current = new Ast.Invoke(current, name, arguments(")"));
                } else {
                    current = new Ast.Get(current, name);
                }
            } else if (accept("[")) {
                Expression index = expression();
                expect("]");
                current = new Ast.At(current, index);
            } else {
                return current;
            }
        }
    }

    /** Arguments séparés par des virgules jusqu'au symbole fermant (déjà ouvert). */
    private List<Expression> arguments(String close) {
        List<Expression> arguments = new ArrayList<>();
        if (accept(close)) {
            return arguments;
        }
        do {
            arguments.add(expression());
        } while (accept(","));
        expect(close);
        return arguments;
    }

    private Expression primary() {
        if (atEnd()) {
            throw new SyntaxException("expression incomplète dans { " + source.strip() + " }");
        }
        Tok token = tokens.get(pos++);
        return switch (token) {
            case Value(var value, _) -> new Ast.Literal(value);
            case Text(var parts, _) -> new Ast.StringExpression(parts);
            case Variable(var name, _) -> new Ast.VariableExpression(name, List.of());
            case Ident(var name, _) when name.equals("true") -> new Ast.Literal(Boolean.TRUE);
            case Ident(var name, _) when name.equals("false") -> new Ast.Literal(Boolean.FALSE);
            case Ident(var name, _) when name.equals("null") -> new Ast.Literal(null);
            case Ident(var name, _) when name.equals("now") -> new Ast.Now();
            case Ident(var name, var at) -> throw new SyntaxException("« " + name + " » inconnu dans le bloc"
                    + " (position " + (at + 1) + ") ; une variable s'écrit $" + name);
            case Symbol(var text, _) when text.equals("(") -> {
                Expression inner = expression();
                expect(")");
                yield inner;
            }
            case Symbol(var text, _) when text.equals("[") -> new Ast.ListLiteral(arguments("]"));
            case Symbol _ -> throw unexpected(token);
        };
    }

    private boolean accept(String symbol) {
        if (!atEnd() && peek() instanceof Symbol(var text, _) && text.equals(symbol)) {
            pos++;
            return true;
        }
        return false;
    }

    private void expect(String symbol) {
        if (!accept(symbol)) {
            throw atEnd() ? new SyntaxException("« " + symbol + " » manquant dans { " + source.strip() + " }")
                    : new SyntaxException("« " + symbol + " » attendu" + where());
        }
    }

    private SyntaxException unexpected(Tok token) {
        String text = switch (token) {
            case Value(var value, _) -> String.valueOf(value);
            case Text _ -> "chaîne";
            case Ident(var name, _) -> name;
            case Variable(var name, _) -> "$" + name;
            case Symbol(var symbol, _) -> symbol;
        };
        return new SyntaxException("« " + text + " » inattendu dans le bloc (position " + (token.at() + 1) + ")");
    }

    private String where() {
        return atEnd() ? " en fin de bloc" : " à la position " + (peek().at() + 1) + " du bloc";
    }

    private Tok peek() {
        return tokens.get(pos);
    }

    private boolean atEnd() {
        return pos >= tokens.size();
    }

    /** Découpage du texte du bloc. */
    private static final class Scanner {

        private final String input;
        private int pos;

        Scanner(String input) {
            this.input = input;
        }

        List<Tok> run() {
            List<Tok> tokens = new ArrayList<>();
            while (true) {
                while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                    pos++;
                }
                if (pos >= input.length()) {
                    return tokens;
                }
                tokens.add(next());
            }
        }

        private Tok next() {
            int start = pos;
            char c = input.charAt(pos);
            if (c == '"') {
                var scanned = Lexer.stringAt(input, pos);
                pos = scanned.end();
                return new Text(scanned.value().parts(), start);
            }
            if (c == '\'') {
                return new Value(character(), start);
            }
            if (c == '$') {
                pos++;
                return new Variable(variableName(start), start);
            }
            if (Character.isDigit(c)) {
                return new Value(number(), start);
            }
            if (Character.isJavaIdentifierStart(c)) {
                while (pos < input.length() && Character.isJavaIdentifierPart(input.charAt(pos))) {
                    pos++;
                }
                return new Ident(input.substring(start, pos), start);
            }
            for (String symbol : SYMBOLS) {
                if (input.startsWith(symbol, pos)) {
                    pos += symbol.length();
                    return new Symbol(symbol, start);
                }
            }
            if (c == '=') {
                throw new SyntaxException("« = » dans un bloc : pour comparer, utiliser == (position " + (pos + 1) + ")");
            }
            throw new SyntaxException("caractère inattendu « " + c + " » dans le bloc (position " + (pos + 1) + ")");
        }

        private String variableName(int dollar) {
            int start = pos;
            if (pos < input.length() && (input.charAt(pos) == '_' || input.charAt(pos) == '?')
                    && (pos + 1 >= input.length() || !Character.isLetterOrDigit(input.charAt(pos + 1)))) {
                pos++;
                return input.substring(start, pos);
            }
            while (pos < input.length() && (Character.isLetterOrDigit(input.charAt(pos)) || input.charAt(pos) == '_')) {
                pos++;
            }
            if (pos == start) {
                throw new SyntaxException("nom de variable attendu après $ (position " + (dollar + 1) + ")");
            }
            return input.substring(start, pos);
        }

        /** Entier, décimal, suffixe {@code L}, ou littéral d'unité ({@code 10kb}, {@code 7d}). */
        private Object number() {
            int start = pos;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                pos++;
            }
            if (pos + 1 < input.length() && input.charAt(pos) == '.' && Character.isDigit(input.charAt(pos + 1))) {
                pos++;
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) {
                    pos++;
                }
            }
            String digits = input.substring(start, pos);
            int suffixStart = pos;
            while (pos < input.length() && Character.isLetter(input.charAt(pos))) {
                pos++;
            }
            String suffix = input.substring(suffixStart, pos);
            try {
                if (suffix.isEmpty()) {
                    if (digits.contains(".")) {
                        return Double.valueOf(digits);
                    }
                    long value = Long.parseLong(digits);
                    return value == (int) value ? (Object) (int) value : (Object) value;
                }
                if (suffix.equals("L") && !digits.contains(".")) {
                    return Long.valueOf(digits);
                }
            } catch (NumberFormatException _) {
                throw new SyntaxException("nombre trop grand : " + digits);
            }
            return Units.parse(digits + suffix).orElseThrow(() -> new SyntaxException("nombre invalide : "
                    + digits + suffix + " (unités : b kb mb gb tb, ms s m h d)"));
        }

        private Character character() {
            int open = pos++;
            if (pos >= input.length()) {
                throw new SyntaxException("caractère non fermé (position " + (open + 1) + ")");
            }
            char value = input.charAt(pos++);
            if (value == '\\') {
                if (pos >= input.length()) {
                    throw new SyntaxException("caractère non fermé (position " + (open + 1) + ")");
                }
                char e = input.charAt(pos++);
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
                        if (pos + 4 > input.length()) {
                            throw new SyntaxException("échappement \\u incomplet");
                        }
                        try {
                            char u = (char) Integer.parseInt(input.substring(pos, pos + 4), 16);
                            pos += 4;
                            yield u;
                        } catch (NumberFormatException _) {
                            throw new SyntaxException("échappement \\u" + input.substring(pos, pos + 4) + " invalide");
                        }
                    }
                    default -> throw new SyntaxException("échappement inconnu \\" + e);
                };
            } else if (value == '\'') {
                throw new SyntaxException("caractère vide '' (position " + (open + 1) + ")");
            }
            if (pos >= input.length() || input.charAt(pos) != '\'') {
                throw new SyntaxException("un caractère s'écrit entre apostrophes : 'a' ; pour une chaîne, utiliser \"…\""
                        + " (position " + (open + 1) + ")");
            }
            pos++;
            return value;
        }
    }
}
