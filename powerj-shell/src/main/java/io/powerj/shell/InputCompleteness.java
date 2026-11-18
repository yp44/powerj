package io.powerj.shell;

import java.util.ArrayDeque;
import java.util.Deque;

import io.powerj.core.lang.Scanning;

/**
 * Determines whether input is complete or whether the shell must wait for a continuation line
 * (specification FR-02): unclosed string or text block, open brace/parenthesis/bracket, or line ending
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

    /**
     * {@link Result.Incomplete#missing()} for a line ending with an operator; internal value, never displayed
     * (the continuation prompt does not use JLine's {@code %M}).
     */
    static final String MISSING_COMMAND = "command";

    private InputCompleteness() {
    }

    static Result check(String input) {
        Deque<Character> open = new ArrayDeque<>();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '"', '\'' -> {
                    // One rule for "where does the quoted text end", shared with the Lexer and PreviewCut: strings with
                    // their interpolations, text blocks and character literals (U-01, C-01).
                    int end = Scanning.quotedEnd(input, i);
                    if (end >= 0) {
                        i = end - 1;
                    } else if (c == '"') {
                        return new Result.Incomplete("\"", open.size());
                    } // a lone ' is an apostrophe, as in "echo it's": not a literal that could ever be closed
                }
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
        if (!open.isEmpty()) {
            return new Result.Incomplete(String.valueOf(open.peek()), open.size());
        }
        var trimmed = input.stripTrailing();
        if (trimmed.endsWith("|") || trimmed.endsWith("&&")) {
            return new Result.Incomplete(MISSING_COMMAND, 0);
        }
        return new Result.Complete();
    }
}
