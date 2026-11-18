package io.powerj.shell;

import io.powerj.core.lang.Scanning;

/**
 * Part of the line previewed (FR-66): the pipeline up to the end of the stage where the cursor is, so that moving
 * the cursor into a stage shows what that stage produces. The {@code |} inside a block, parentheses, brackets, a
 * string or a character literal do not separate stages.
 */
final class PreviewCut {

    private PreviewCut() {
    }

    /** {@code line} cut before the first stage separator {@code |} at or after {@code cursor}. */
    static String upToStage(String line, int cursor) {
        int depth = 0;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '"' || c == '\'') {
                int end = Scanning.quotedEnd(line, i);
                if (end >= 0) {
                    i = end;
                    continue;
                }
                if (c == '"') {
                    return line; // an unclosed string runs to the end of the line
                }
                // a lone ' is an apostrophe: an ordinary character
            }
            switch (c) {
                case '(', '{', '[' -> depth++;
                case ')', '}', ']' -> depth = Math.max(0, depth - 1);
                case '|' -> {
                    boolean double_ = i + 1 < line.length() && line.charAt(i + 1) == '|';
                    if (double_) {
                        i += 2; // || chains commands (FR-04c): not a stage separator
                        continue;
                    }
                    if (depth == 0 && i >= cursor) {
                        return line.substring(0, i);
                    }
                }
                default -> { }
            }
            i++;
        }
        return line;
    }
}
