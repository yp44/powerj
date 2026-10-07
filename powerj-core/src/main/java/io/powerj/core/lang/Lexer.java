package io.powerj.core.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Découpe une ligne en {@link Token}. Règles (spécification §3.8, §12.1) :
 * <ul>
 *   <li>les mots non quotés sont pris tels quels, antislash compris ({@code cd C:\Users}) ;</li>
 *   <li>{@code ;}, {@code &&}, {@code ||}, {@code |}, {@code >}, {@code >>}, {@code 2>}, {@code 2>>},
 *       {@code 2>&1} sont des opérateurs, même collés à un mot ;</li>
 *   <li>{@code $x =} en tête d'instruction marque une affectation ;</li>
 *   <li>les expressions sont confiées à {@link ExpressionParser} : chaînes {@code "…"}, variables, blocs
 *       {@code { … }}, groupes {@code ( … )}, et en tête d'instruction les littéraux, {@code new}, les
 *       conversions {@code [type]} et les noms Java ({@link #expressionAt}) ;</li>
 *   <li>{@code ==} et {@code >=} sont des mots (forme courte de {@code where}).</li>
 * </ul>
 */
public final class Lexer {

    /** Nom qualifié {@code ident(.ident)+}, suivi éventuellement de {@code (}. */
    private static final Pattern QUALIFIED = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*(?:\\.[\\p{L}_][\\p{L}\\p{N}_]*)+");

    private static final Set<String> KEYWORDS = Set.of("true", "false", "null", "now");

    private final String input;
    private final Predicate<String> staticNames;
    private int pos;

    private Lexer(String input, Predicate<String> staticNames) {
        this.input = input;
        this.staticNames = staticNames;
    }

    public static List<Token> tokenize(String input) {
        return tokenize(input, _ -> false);
    }

    /**
     * @param staticNames reconnaît les noms qualifiés désignant une classe ou un champ statique Java
     *                    ({@code Math.PI}) : en tête d'instruction, ce sont des expressions (FR-46)
     */
    public static List<Token> tokenize(String input, Predicate<String> staticNames) {
        return new Lexer(input, staticNames).run();
    }

    private List<Token> run() {
        List<Token> tokens = new ArrayList<>();
        while (true) {
            skipWhitespace();
            if (atEnd()) {
                return tokens;
            }
            boolean head = tokens.isEmpty() || tokens.getLast() instanceof Token.Separator
                    || tokens.getLast() instanceof Token.Pipe || tokens.getLast() instanceof Token.AssignTo;
            tokens.add(next(head, tokens.isEmpty() || tokens.getLast() instanceof Token.Separator));
        }
    }

    /**
     * @param head      position de commande (début d'instruction, après {@code |} ou une affectation)
     * @param statement début d'instruction : une affectation est possible
     */
    private Token next(boolean head, boolean statement) {
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
        switch (c) {
            case ';' -> {
                pos++;
                return new Token.Separator(Connector.ALWAYS);
            }
            case '|' -> {
                pos++;
                return new Token.Pipe();
            }
            case '&' -> throw new SyntaxException("« & » isolé n'est pas supporté (utiliser && ou ;)");
            case ')' -> throw new SyntaxException("« ) » inattendu (position " + (pos + 1) + ")");
            case '}' -> throw new SyntaxException("« } » sans « { » correspondante (position " + (pos + 1) + ")");
            case '=' -> throw new SyntaxException("« = » inattendu (position " + (pos + 1) + ") ; affectation : $nom = valeur");
            default -> { }
        }
        if (statement) {
            var assignment = assignment();
            if (assignment != null) {
                return assignment;
            }
        }
        if (head ? expressionAt(input, pos, staticNames) : argumentExpressionAt()) {
            var scanned = ExpressionParser.scan(input, pos,
                    head ? ExpressionParser.Mode.STATEMENT : ExpressionParser.Mode.ARGUMENT, staticNames);
            pos = scanned.end();
            return new Token.Expr(scanned.value());
        }
        return word();
    }

    /** {@code $nom =} (mais pas {@code $nom ==}). */
    private Token.AssignTo assignment() {
        if (peek() != '$' || !isVariableStart(pos + 1)) {
            return null;
        }
        int end = pos + 1;
        while (end < input.length() && (Character.isLetterOrDigit(input.charAt(end)) || input.charAt(end) == '_')) {
            end++;
        }
        int after = ExpressionParser.skipBlanks(input, end);
        if (after < input.length() && input.charAt(after) == '=' && !input.startsWith("==", after)) {
            String name = input.substring(pos + 1, end);
            pos = after + 1;
            return new Token.AssignTo(name);
        }
        return null;
    }

    /**
     * Une expression commence-t-elle en {@code at}, en position de commande ? Variables, chaînes, groupes,
     * blocs, listes et conversions, caractères, nombres, {@code true}/{@code false}/{@code null}/{@code now},
     * {@code new Classe(…)}, et noms qualifiés collés à {@code (} ou désignant une classe ou un champ
     * statique ({@code Math.max(3, 7)}, {@code java.lang.Math.PI}). Sinon c'est une commande
     * ({@code java -version}, {@code notepad.exe x}).
     */
    static boolean expressionAt(String input, int at, Predicate<String> staticNames) {
        if (at >= input.length()) {
            return false;
        }
        char c = input.charAt(at);
        if (c == '$') {
            return at + 1 < input.length() && (isIdentifierStart(input.charAt(at + 1)) || input.charAt(at + 1) == '?');
        }
        if (c == '"' || c == '(' || c == '{' || c == '[' || c == '\'' || Character.isDigit(c)) {
            return true;
        }
        if (!isIdentifierStart(c)) {
            return false;
        }
        int end = at;
        while (end < input.length() && !isWordEnd(input, end)) {
            end++;
        }
        String word = input.substring(at, end);
        if (KEYWORDS.contains(word)) {
            return true;
        }
        if (word.equals("new") && end < input.length() && isBlank(input.charAt(end))) {
            int next = ExpressionParser.skipBlanks(input, end);
            return next < input.length() && isIdentifierStart(input.charAt(next));
        }
        Matcher qualified = QUALIFIED.matcher(input).region(at, input.length());
        if (qualified.lookingAt()) {
            int chainEnd = qualified.end();
            if (chainEnd < input.length() && input.charAt(chainEnd) == '(') {
                return true;
            }
            return chainEnd == end && staticNames.test(input.substring(at, chainEnd));
        }
        return false;
    }

    /** En argument : variable, chaîne, groupe, bloc, ou appel Java collé ({@code Path.of("x")}). */
    private boolean argumentExpressionAt() {
        char c = peek();
        if (c == '$') {
            return isVariableStart(pos + 1);
        }
        if (c == '"' || c == '(' || c == '{') {
            return true;
        }
        Matcher qualified = QUALIFIED.matcher(input).region(pos, input.length());
        return qualified.lookingAt() && qualified.end() < input.length() && input.charAt(qualified.end()) == '(';
    }

    private Token.Word word() {
        int start = pos;
        while (!atEnd() && !isWordEnd(input, pos)) {
            pos++;
        }
        return new Token.Word(input.substring(start, pos));
    }

    private static boolean isWordEnd(String input, int at) {
        char c = input.charAt(at);
        return isBlank(c) || c == ';' || c == '|' || c == '"' || c == '>' || c == '(' || c == ')'
                || c == '{' || c == '}'
                || input.startsWith("&&", at)
                || (c == '2' && input.startsWith("2>", at) && (at == 0 || isBlank(input.charAt(at - 1))));
    }

    /**
     * Séparateur : espace, tabulation, fin de ligne, mais aussi les espaces insécables (U+00A0, U+202F), que
     * le clavier français produit facilement en tapant AltGr+Espace juste après {@code |} (AltGr+6).
     */
    public static boolean isBlank(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
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

    /** Chaîne qui commence en {@code start}, avec ses morceaux, et position qui la suit. */
    static Scanned<List<StringPart>> stringAt(String input, int start, Predicate<String> staticNames) {
        var lexer = new Lexer(input, staticNames);
        lexer.pos = start;
        List<StringPart> parts = lexer.string();
        return new Scanned<>(parts, lexer.pos);
    }

    /** Élément lu et position qui le suit. */
    record Scanned<T>(T value, int end) { }

    /** {@code "…"} : échappements Java, {@code $var.prop[0]} et {@code $( … )} interpolés. */
    private List<StringPart> string() {
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
                    return List.copyOf(parts);
                }
                case '\\' -> text.append(escape());
                case '$' -> {
                    if (isVariableStart(pos) || (!atEnd() && peek() == '(')) {
                        if (!text.isEmpty()) {
                            parts.add(new StringPart.Text(text.toString()));
                            text.setLength(0);
                        }
                        if (peek() == '(') {
                            var group = ExpressionParser.group(input, pos, staticNames);
                            pos = group.end();
                            parts.add(new StringPart.Embedded(group.value()));
                        } else {
                            String name = identifier();
                            parts.add(new StringPart.Interpolation(name, accessors()));
                        }
                    } else {
                        text.append('$');
                    }
                }
                default -> text.append(c);
            }
        }
    }

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
        while (!atEnd() && isBlank(peek())) {
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
