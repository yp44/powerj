package io.powerj.core.lang;

import java.util.ArrayList;
import java.util.List;

/**
 * The reading of a line that needs no syntax tree: where a string ends, which symbol closes a group, where the
 * blanks stop. The parser, the highlighting and the completion read strings and groups through this class, so a
 * string with an interpolation of strings of its own ({@code "a $(f("b")) c"}) ends at the same place for all of them.
 */
public final class Scanning {

    private Scanning() {
    }

    /** Position of the first character at or after {@code from} that is not a blank. */
    public static int skipBlanks(String text, int from) {
        int at = from;
        while (at < text.length() && Lexer.isBlank(text.charAt(at))) {
            at++;
        }
        return at;
    }

    /**
     * Position following the string opened at {@code open}; -1 if it is not closed. A {@code $( … )} interpolation
     * may hold strings and groups of its own, and what is in them does not end the string.
     */
    public static int stringEnd(String text, int open) {
        int at = open + 1;
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == '\\') {
                at += 2;
            } else if (c == '"') {
                return at + 1;
            } else if (c == '$' && at + 1 < text.length() && text.charAt(at + 1) == '(') {
                int close = matching(text, at + 1);
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

    /**
     * Position of the closing symbol matching the one opened at {@code open} ({@code (}, {@code [} or
     * {@code {}), ignoring the content of strings and characters; -1 if it is missing.
     */
    public static int matching(String text, int open) {
        List<Character> expected = new ArrayList<>();
        int at = open;
        while (at < text.length()) {
            char c = text.charAt(at);
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
                    at = stringEnd(text, at);
                    if (at < 0) {
                        return -1;
                    }
                    continue;
                }
                case '\'' -> {
                    int end = characterEnd(text, at);
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

    /** End of a character literal ({@code 'a'}, {@code '\n'}, {@code 'é'}) at {@code open}, otherwise -1. */
    private static int characterEnd(String text, int open) {
        if (open + 2 < text.length() && text.charAt(open + 1) != '\\' && text.charAt(open + 2) == '\'') {
            return open + 3;
        }
        if (open + 3 < text.length() && text.charAt(open + 1) == '\\' && text.charAt(open + 3) == '\'') {
            return open + 4;
        }
        if (open + 7 < text.length() && text.startsWith("\\u", open + 1) && text.charAt(open + 7) == '\'') {
            return open + 8;
        }
        return -1;
    }
}
