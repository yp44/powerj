package io.powerj.core.lang;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a line into {@link Token}s. Rules (specification §3.8, §12.1):
 * <ul>
 *   <li>unquoted words are taken as is, backslashes included ({@code cd C:\Users});</li>
 *   <li>{@code ;}, {@code &&}, {@code ||}, {@code |}, {@code >}, {@code >>}, {@code 2>}, {@code 2>>},
 *       {@code 2>&1} are operators, even when attached to a word;</li>
 *   <li>{@code $x =} at the start of a statement marks an assignment;</li>
 *   <li>expressions are handed to {@link ExpressionParser}: strings {@code "…"}, variables, blocks
 *       {@code { … }}, groups {@code ( … )}, and at the start of a statement literals, {@code new},
 *       {@code [type]} conversions and Java names ({@link #expressionAt});</li>
 *   <li>{@code ==} and {@code >=} are words (short form of {@code where}).</li>
 * </ul>
 */
public final class Lexer {

    /** Method reference {@code Class::method} or {@code qualified.Name::method}. */
    private static final Pattern METHOD_REFERENCE = Pattern.compile(
            "[\\p{L}_][\\p{L}\\p{N}_]*(?:\\.[\\p{L}_][\\p{L}\\p{N}_]*)*::[\\p{L}_]");

    /** Qualified name {@code ident(.ident)+}, optionally followed by {@code (}. */
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
     * @param staticNames recognizes qualified names designating a Java class or static field
     *                    ({@code Math.PI}): at the start of a statement, they are expressions (FR-46)
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
     * @param head      command position (start of statement, after {@code |} or an assignment)
     * @param statement start of statement: an assignment is possible
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
        if (startsWith("->")) {
            throw new SyntaxException("« -> » hors d'un bloc : en argument d'une commande, une lambda s'écrit"
                    + " entre accolades, ex. where { f -> f.size > 1mb }");
        }
        if (startsWith("2>&1")) {
            pos += 4;
            return new Token.Redirection(Token.Stream.ERR_TO_OUT, false);
        }
        if (startsWith(">=") || startsWith("==")) {
            pos += 2;
            return new Token.Word(input.substring(pos - 2, pos)); // operators of the short form of where
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

    /** {@code $name =} (but not {@code $name ==}). */
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
     * Does an expression start at {@code at}, in command position? Variables, strings, groups,
     * blocks, lists and conversions, characters, numbers, {@code true}/{@code false}/{@code null}/{@code now},
     * {@code new Class(…)}, and qualified names attached to {@code (} or designating a class or a static
     * field ({@code Math.max(3, 7)}, {@code java.lang.Math.PI}). Otherwise it is a command
     * ({@code java -version}, {@code notepad.exe x}).
     */
    public static boolean expressionAt(String input, int at, Predicate<String> staticNames) {
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
        if (KEYWORDS.contains(word) || METHOD_REFERENCE.matcher(input).region(at, input.length()).lookingAt()) {
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
        return ExpressionParser.isParameter(staticNames, word);
    }

    /** As an argument: variable, string, group, block, or attached Java call ({@code Path.of("x")}). */
    private boolean argumentExpressionAt() {
        char c = peek();
        if (c == '$') {
            return isVariableStart(pos + 1);
        }
        if (c == '"' || c == '(' || c == '{') {
            return true;
        }
        if (METHOD_REFERENCE.matcher(input).region(pos, input.length()).lookingAt()) {
            return true; // map FileEntry::name
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
     * Separator: space, tab, end of line, but also non-breaking spaces (U+00A0, U+202F), which
     * the French keyboard easily produces when typing AltGr+Space right after {@code |} (AltGr+6).
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
            return input.substring(start, pos); // $? and $_
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

    /** String starting at {@code start}, with its parts, and the position following it. */
    static Scanned<List<StringPart>> stringAt(String input, int start, Predicate<String> staticNames) {
        var lexer = new Lexer(input, staticNames);
        lexer.pos = start;
        List<StringPart> parts = lexer.string();
        return new Scanned<>(parts, lexer.pos);
    }

    /** Element read and the position following it. */
    record Scanned<T>(T value, int end) { }

    /** {@code "…"}: Java escapes, {@code $var.prop[0]} and {@code $( … )} interpolated. */
    private List<StringPart> string() {
        if (startsWith("\"\"\"")) {
            return textBlock();
        }
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

    /**
     * Text block {@code """…"""} (FR-33b): as in Java, it starts with a line break and
     * the common indentation is removed; escapes and interpolations apply within it.
     */
    private List<StringPart> textBlock() {
        int open = pos;
        int at = pos + 3;
        while (at < input.length() && (input.charAt(at) == ' ' || input.charAt(at) == '\t')) {
            at++;
        }
        if (at < input.length() && input.charAt(at) == '\r') {
            at++;
        }
        if (at >= input.length() || input.charAt(at) != '\n') {
            throw new SyntaxException("un bloc de texte s'ouvre par \"\"\" suivi d'un retour à la ligne (position "
                    + (open + 1) + ")");
        }
        int contentStart = at + 1;
        int close = contentStart;
        while (true) {
            close = input.indexOf("\"\"\"", close);
            if (close < 0) {
                throw new SyntaxException("bloc de texte non fermé (ouvert à la position " + (open + 1) + ")");
            }
            if (!escaped(close)) {
                break;
            }
            close++;
        }
        String content = input.substring(contentStart, close).replace("\r\n", "\n").stripIndent();
        pos = close + 3;
        // Quotes in the content are text: escape them to reuse the string parsing.
        var quoted = new StringBuilder("\"");
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '"' && !escaped(content, i)) {
                quoted.append('\\');
            }
            quoted.append(c);
        }
        quoted.append('"');
        return stringAt(quoted.toString(), 0, staticNames).value();
    }

    private boolean escaped(int at) {
        return escaped(input, at);
    }

    /** Is the character at {@code at} preceded by an odd number of backslashes? */
    private static boolean escaped(String text, int at) {
        int count = 0;
        for (int i = at - 1; i >= 0 && text.charAt(i) == '\\'; i--) {
            count++;
        }
        return count % 2 == 1;
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
