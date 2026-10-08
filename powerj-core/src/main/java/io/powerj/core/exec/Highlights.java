package io.powerj.core.exec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import io.powerj.core.lang.Lexer;

/**
 * Découpe une saisie en zones à colorer (spécification FR-08) : commande interne ou cmdlet, programme
 * natif, commande inconnue, option, chaîne, variable. Tolérant : une saisie incomplète (chaîne non fermée,
 * bloc ouvert) est colorée au mieux, jamais rejetée.
 */
public final class Highlights {

    /** Nature d'une zone. */
    public enum Kind { BUILTIN, CMDLET, NATIVE, UNKNOWN, OPTION, STRING, VARIABLE }

    /** Zone {@code [start, end[} de la saisie. */
    public record Span(int start, int end, Kind kind) { }

    private Highlights() {
    }

    /**
     * @param commands    nature d'un nom de commande ({@link Interpreter#commandKind})
     * @param staticNames noms Java désignant une classe ou un champ statique (pour ne pas les colorer en
     *                    commande inconnue)
     */
    public static List<Span> of(String line, Function<String, Interpreter.CommandKind> commands,
                                Predicate<String> staticNames) {
        List<Span> spans = new ArrayList<>();
        Deque<Character> nesting = new ArrayDeque<>();
        boolean head = true;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            boolean command = nesting.isEmpty() || nesting.peek() == '(';
            if (Lexer.isBlank(c)) {
                i++;
                continue;
            }
            if (c == '"') {
                int end = stringEnd(line, i);
                spans.add(new Span(i, end, Kind.STRING));
                i = end;
                head = false;
                continue;
            }
            if (c == '$' && i + 1 < line.length() && isVariableChar(line.charAt(i + 1))) {
                int end = i + 1;
                while (end < line.length() && isVariableChar(line.charAt(end))) {
                    end++;
                }
                spans.add(new Span(i, end, Kind.VARIABLE));
                int next = skipBlanks(line, end);
                head = head && command && next < line.length() && line.charAt(next) == '='
                        && !line.startsWith("==", next);
                i = end;
                continue;
            }
            switch (c) {
                case '{', '[' -> {
                    nesting.push(c);
                    i++;
                    continue;
                }
                case '(' -> {
                    boolean call = i > 0 && Character.isJavaIdentifierPart(line.charAt(i - 1));
                    nesting.push(call ? '[' : '('); // un appel Java ne contient pas de commande
                    head = !call;
                    i++;
                    continue;
                }
                case '}', ']', ')' -> {
                    if (!nesting.isEmpty()) {
                        nesting.pop();
                    }
                    head = false;
                    i++;
                    continue;
                }
                case '|', ';', '&' -> {
                    if (command) {
                        head = true;
                    }
                    i++;
                    continue;
                }
                case '=' -> {
                    i++;
                    continue;
                }
                default -> { }
            }
            int end = wordEnd(line, i);
            if (!command) {
                i = Math.max(end, i + 1);
                continue;
            }
            String word = line.substring(i, end);
            if (head) {
                if (!Lexer.expressionAt(line, i, staticNames) && !word.isEmpty()) {
                    spans.add(new Span(i, end, switch (commands.apply(word)) {
                        case BUILTIN -> Kind.BUILTIN;
                        case CMDLET -> Kind.CMDLET;
                        case NATIVE -> Kind.NATIVE;
                        case UNKNOWN -> Kind.UNKNOWN;
                    }));
                }
                head = false;
            } else if (word.startsWith("-") && word.length() > 1 && !word.startsWith("->")) {
                spans.add(new Span(i, end, Kind.OPTION));
            }
            i = Math.max(end, i + 1);
        }
        return spans;
    }

    private static boolean isVariableChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '?';
    }

    private static int stringEnd(String line, int open) {
        int i = open + 1;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == '"') {
                return i + 1;
            } else {
                i++;
            }
        }
        return line.length();
    }

    private static int wordEnd(String line, int start) {
        int i = start;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (Lexer.isBlank(c) || c == '|' || c == ';' || c == '"' || c == '(' || c == ')' || c == '{' || c == '}'
                    || c == '>' && i > start) {
                break;
            }
            i++;
        }
        return i;
    }

    private static int skipBlanks(String line, int from) {
        int i = from;
        while (i < line.length() && Lexer.isBlank(line.charAt(i))) {
            i++;
        }
        return i;
    }
}
