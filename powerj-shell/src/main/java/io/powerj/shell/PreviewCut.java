package io.powerj.shell;

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
                i = endOfQuoted(line, i);
                continue;
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

    /** Position after the string, text block or character literal that starts at {@code start}. */
    private static int endOfQuoted(String line, int start) {
        char quote = line.charAt(start);
        if (quote == '"' && line.startsWith("\"\"\"", start)) {
            int end = line.indexOf("\"\"\"", start + 3);
            return end < 0 ? line.length() : end + 3;
        }
        int i = start + 1;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == quote) {
                return i + 1;
            }
            i++;
        }
        return line.length();
    }
}
