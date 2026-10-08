package io.powerj.shell;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Determines whether input is complete or whether the shell must wait for a continuation line
 * (specification FR-02): unclosed string, open brace/parenthesis/bracket, or line ending
 * with {@code |}, {@code &&} or {@code ||}. A backslash at the end of the line does not trigger
 * continuation, so that {@code cd C:\} remains valid.
 */
final class InputCompleteness {

    /** Result of the analysis. */
    sealed interface Result {
        record Complete() implements Result { }

        /**
         * @param missing      what is missing, for the message ({@code "}"}, {@code "\""}, a command…)
         * @param openBrackets number of braces, parentheses and brackets still open
         */
        record Incomplete(String missing, int openBrackets) implements Result { }
    }

    private InputCompleteness() {
    }

    static Result check(String input) {
        Deque<Character> open = new ArrayDeque<>();
        boolean inString = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++; // escaped character, including \"
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '(' -> open.push(')');
                case '{' -> open.push('}');
                case '[' -> open.push(']');
                case ')', '}', ']' -> {
                    if (!open.isEmpty() && open.peek() == c) {
                        open.pop();
                    }
                }
                default -> { }
            }
        }
        if (inString) {
            return new Result.Incomplete("\"", open.size());
        }
        if (!open.isEmpty()) {
            return new Result.Incomplete(String.valueOf(open.peek()), open.size());
        }
        var trimmed = input.stripTrailing();
        if (trimmed.endsWith("|") || trimmed.endsWith("&&")) {
            return new Result.Incomplete("commande", 0);
        }
        return new Result.Complete();
    }
}
