package io.powerj.core.lang;

import java.util.ArrayList;
import java.util.List;

/**
 * Découpe une ligne en {@link Token}. Règles (spécification §3.8) :
 * <ul>
 *   <li>les mots non quotés sont pris tels quels, antislash compris ({@code cd C:\Users}) ;</li>
 *   <li>les chaînes {@code "…"} suivent les échappements Java et interpolent {@code $var} ;</li>
 *   <li>{@code $nom.prop[0]} est une référence de variable ;</li>
 *   <li>{@code ;}, {@code &&}, {@code ||}, {@code |}, {@code >}, {@code >>}, {@code 2>}, {@code 2>>}
 *       sont des opérateurs, même collés à un mot ;</li>
 *   <li>{@code =} isolé (après une variable) marque une affectation ;</li>
 *   <li>{@code ( … )} délimite une sous-expression, éventuellement suivie d'accès : {@code (ls).name} ;</li>
 *   <li>{@code { … }} délimite un bloc d'expression, analysé par {@link ExpressionParser} ;</li>
 *   <li>{@code 2>&1} envoie les erreurs dans la sortie ; {@code ==} et {@code >=} sont des mots (forme
 *       courte de {@code where}).</li>
 * </ul>
 */
public final class Lexer {

    private final String input;
    private int pos;

    private Lexer(String input) {
        this.input = input;
    }

    public static List<Token> tokenize(String input) {
        return new Lexer(input).run();
    }

    private List<Token> run() {
        List<Token> tokens = new ArrayList<>();
        while (true) {
            skipWhitespace();
            if (atEnd()) {
                return tokens;
            }
            tokens.add(next());
        }
    }

    private Token next() {
        char c = peek();
        if (startsWith("&&")) {
            pos += 2;
            return new Token.Separator(Connector.IF_SUCCESS);
        }
        if (startsWith("||")) {
            pos += 2;
            return new Token.Separator(Connector.IF_FAILURE);
        }
        if (startsWith("2>&1")) {
            pos += 4;
            return new Token.Redirection(Token.Stream.ERR_TO_OUT, false);
        }
        if (startsWith(">=") || startsWith("==")) {
            pos += 2;
            return new Token.Word(input.substring(pos - 2, pos)); // opérateurs de la forme courte de where
        }
        if (startsWith("2>>") || startsWith(">>")) {
            var stream = c == '2' ? Token.Stream.ERR : Token.Stream.OUT;
            pos += c == '2' ? 3 : 2;
            return new Token.Redirection(stream, true);
        }
        if (startsWith("2>") || c == '>') {
            var stream = c == '2' ? Token.Stream.ERR : Token.Stream.OUT;
            pos += c == '2' ? 2 : 1;
            return new Token.Redirection(stream, false);
        }
        return switch (c) {
            case ';' -> {
                pos++;
                yield new Token.Separator(Connector.ALWAYS);
            }
            case '|' -> {
                pos++;
                yield new Token.Pipe();
            }
            case '&' -> throw new SyntaxException("« & » isolé n'est pas supporté (utiliser && ou ;)");
            case '=' -> {
                pos++;
                yield new Token.Assign();
            }
            case '(' -> {
                pos++;
                yield new Token.Open();
            }
            case ')' -> {
                pos++;
                yield new Token.Close(accessors());
            }
            case '"' -> string();
            case '{' -> block();
            case '}' -> throw new SyntaxException("« } » sans « { » correspondante (position " + (pos + 1) + ")");
            case '$' -> isVariableStart(pos + 1) ? variable() : word();
            default -> word();
        };
    }

    private Token.Word word() {
        int start = pos;
        while (!atEnd() && !isWordEnd()) {
            pos++;
        }
        return new Token.Word(input.substring(start, pos));
    }

    private boolean isWordEnd() {
        char c = peek();
        return Character.isWhitespace(c) || c == ';' || c == '|' || c == '"' || c == '>' || c == '(' || c == ')'
                || c == '{' || c == '}'
                || startsWith("&&") || (c == '2' && startsWith("2>") && atWordStart());
    }

    /** {@code 2>} n'est une redirection qu'en début de mot ({@code a2>b} reste un mot suivi de {@code >}). */
    private boolean atWordStart() {
        return pos == 0 || Character.isWhitespace(input.charAt(pos - 1));
    }

    private Token.Var variable() {
        pos++; // $
        String name = identifier();
        return new Token.Var(name, accessors());
    }

    private List<Accessor> accessors() {
        List<Accessor> accessors = new ArrayList<>();
        while (!atEnd()) {
            if (peek() == '.' && pos + 1 < input.length() && isIdentifierStart(input.charAt(pos + 1))) {
                pos++;
                accessors.add(new Accessor.Property(identifier()));
            } else if (peek() == '[') {
                accessors.add(new Accessor.Index(index()));
            } else {
                break;
            }
        }
        return List.copyOf(accessors);
    }

    private int index() {
        int open = pos++;
        int start = pos;
        if (!atEnd() && peek() == '-') {
            pos++;
        }
        while (!atEnd() && Character.isDigit(peek())) {
            pos++;
        }
        if (atEnd() || peek() != ']' || pos == start || input.substring(start, pos).equals("-")) {
            throw new SyntaxException("index invalide à la position " + (open + 1) + " (attendu : [nombre])");
        }
        int value = Integer.parseInt(input.substring(start, pos));
        pos++; // ]
        return value;
    }

    private String identifier() {
        int start = pos;
        if (!atEnd() && (peek() == '?' || peek() == '_') && (pos + 1 >= input.length()
                || !Character.isLetterOrDigit(input.charAt(pos + 1)))) {
            pos++;
            return input.substring(start, pos); // $? et $_
        }
        while (!atEnd() && (Character.isLetterOrDigit(peek()) || peek() == '_')) {
            pos++;
        }
        return input.substring(start, pos);
    }

    private boolean isVariableStart(int at) {
        return at < input.length() && (isIdentifierStart(input.charAt(at)) || input.charAt(at) == '?');
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private Token.Str string() {
        int open = pos++;
        List<StringPart> parts = new ArrayList<>();
        var text = new StringBuilder();
        while (true) {
            if (atEnd()) {
                throw new SyntaxException("chaîne non fermée (ouverte à la position " + (open + 1) + ")");
            }
            char c = input.charAt(pos++);
            switch (c) {
                case '"' -> {
                    if (!text.isEmpty() || parts.isEmpty()) {
                        parts.add(new StringPart.Text(text.toString()));
                    }
                    return new Token.Str(List.copyOf(parts));
                }
                case '\\' -> text.append(escape());
                case '$' -> {
                    if (isVariableStart(pos)) {
                        if (!text.isEmpty()) {
                            parts.add(new StringPart.Text(text.toString()));
                            text.setLength(0);
                        }
                        String name = identifier();
                        parts.add(new StringPart.Interpolation(name, accessors()));
                    } else {
                        text.append('$');
                    }
                }
                default -> text.append(c);
            }
        }
    }

    /** {@code { … }} : texte jusqu'à l'accolade fermante correspondante, chaînes et caractères compris. */
    private Token.Block block() {
        int open = pos++;
        int depth = 1;
        while (!atEnd()) {
            char c = input.charAt(pos);
            switch (c) {
                case '"' -> {
                    string(); // valide la chaîne et avance après elle
                    continue;
                }
                case '\'' -> {
                    pos++;
                    while (!atEnd() && input.charAt(pos) != '\'') {
                        pos += input.charAt(pos) == '\\' ? 2 : 1;
                    }
                }
                case '{' -> depth++;
                case '}' -> {
                    if (--depth == 0) {
                        String source = input.substring(open + 1, pos);
                        pos++;
                        return new Token.Block(source);
                    }
                }
                default -> { }
            }
            pos++;
        }
        throw new SyntaxException("bloc non fermé (« { » à la position " + (open + 1) + ")");
    }

    /** Lit la chaîne qui commence à {@code start} ; utilisé aussi par l'analyse des expressions. */
    static Scanned<Token.Str> stringAt(String input, int start) {
        var lexer = new Lexer(input);
        lexer.pos = start;
        Token.Str str = lexer.string();
        return new Scanned<>(str, lexer.pos);
    }

    /** Élément lu et position qui le suit. */
    record Scanned<T>(T value, int end) { }

    private String escape() {
        if (atEnd()) {
            throw new SyntaxException("antislash en fin de chaîne");
        }
        char c = input.charAt(pos++);
        return switch (c) {
            case '\\' -> "\\";
            case '"' -> "\"";
            case '$' -> "$";
            case 'n' -> "\n";
            case 't' -> "\t";
            case 'r' -> "\r";
            case 'b' -> "\b";
            case 'f' -> "\f";
            case '0' -> "\0";
            case 'u' -> unicodeEscape();
            default -> throw new SyntaxException("échappement inconnu \\" + c + " (pour un antislash, écrire \\\\)");
        };
    }

    private String unicodeEscape() {
        if (pos + 4 > input.length()) {
            throw new SyntaxException("échappement \\u incomplet");
        }
        String hex = input.substring(pos, pos + 4);
        try {
            pos += 4;
            return String.valueOf((char) Integer.parseInt(hex, 16));
        } catch (NumberFormatException _) {
            throw new SyntaxException("échappement \\u" + hex + " invalide");
        }
    }

    private void skipWhitespace() {
        while (!atEnd() && Character.isWhitespace(peek())) {
            pos++;
        }
    }

    private boolean startsWith(String s) {
        return input.startsWith(s, pos);
    }

    private char peek() {
        return input.charAt(pos);
    }

    private boolean atEnd() {
        return pos >= input.length();
    }
}
