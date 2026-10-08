package io.powerj.core.exec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import io.powerj.core.lang.ExpressionParser;
import io.powerj.core.lang.Lexer;

/**
 * Splits an input line into spans to highlight (specification FR-08): built-in command or cmdlet, native
 * program, unknown command, option, string, variable. Tolerant: incomplete input (unclosed string,
 * open block) is highlighted as well as possible, never rejected.
 */
public final class Highlights {

    /** Kind of a span. */
    public enum Kind { BUILTIN, CMDLET, NATIVE, UNKNOWN, OPTION, STRING, VARIABLE }

    /** Span {@code [start, end[} of the input. */
    public record Span(int start, int end, Kind kind) { }

    private Highlights() {
    }

    /**
     * @param commands    kind of a command name ({@link Interpreter#commandKind})
     * @param staticNames Java names denoting a class or a static field (so as not to highlight them as an
     *                    unknown command)
     */
    public static List<Span> of(String line, Function<String, Interpreter.CommandKind> commands,
                                Predicate<String> staticNames) {
        List<Span> spans = new ArrayList<>();
        Deque<Character> nesting = new ArrayDeque<>();
        List<Map.Entry<Integer, List<String>>> parameters = new ArrayList<>(); // profondeur → paramètres
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
                    i = c == '{' ? lambda(line, i + 1, nesting.size(), parameters) : i + 1;
                    continue;
                }
                case '(' -> {
                    boolean call = i > 0 && Character.isJavaIdentifierPart(line.charAt(i - 1));
                    nesting.push(call ? '[' : '('); // un appel Java ne contient pas de commande
                    head = !call;
                    i = call ? lambda(line, i + 1, nesting.size(), parameters) : i + 1;
                    continue;
                }
                case '}', ']', ')' -> {
                    if (!nesting.isEmpty()) {
                        nesting.pop();
                    }
                    parameters.removeIf(scope -> scope.getKey() > nesting.size());
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
                case ',' -> {
                    i = command ? i + 1 : lambda(line, i + 1, nesting.size(), parameters);
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
                i++; // caractère par caractère : une virgule peut précéder une lambda
                continue;
            }
            String word = line.substring(i, end);
            if (head) {
                if (!Lexer.expressionAt(line, i, staticNames) && !word.isEmpty() && !isParameter(word, parameters)) {
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

    /** Records the parameters of a lambda whose header starts at {@code at}; returns where to resume. */
    private static int lambda(String line, int at, int depth, List<Map.Entry<Integer, List<String>>> parameters) {
        return ExpressionParser.lambdaHeader(line, at).map(header -> {
            parameters.add(Map.entry(depth, header.getKey()));
            return header.getValue();
        }).orElse(at);
    }

    /** {@code f.size} in {@code f -> (f.size)}: an expression on a parameter, not a command. */
    private static boolean isParameter(String word, List<Map.Entry<Integer, List<String>>> parameters) {
        int dot = word.indexOf('.');
        String name = dot < 0 ? word : word.substring(0, dot);
        return parameters.stream().anyMatch(scope -> scope.getValue().contains(name));
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
