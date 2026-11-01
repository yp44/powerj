package io.powerj.core.error;

/**
 * What of a line typed by the user may be written in the diagnostic log (specification FR-60): the name of the command,
 * and a marker when something follows it. Never the rest.
 * <p>
 * The log is made to be shared in a bug report, and the history already keeps the lines, except those that start with a
 * space (FR-10), which is how a user keeps a line with a secret out of it. The log must not keep what the history does not:
 * <pre>
 * curl -u user:password https://example.org   →   curl [truncated...]
 *  curl -u user:password                      →   curl [truncated...]   (a line that starts with a space)
 * ^git push --force                           →   ^git [truncated...]
 * ls                                          →   ls
 * $token="s3cret"                             →   [truncated...]       (no command name: nothing of the line)
 * </pre>
 * A name starts with a letter or an underscore and goes on with letters, digits and {@code . _ - :}, with an optional
 * leading {@code ^} (a native program); it ends at a blank, at the end of the line or at a separator of statements. A
 * line that does not start with one (an assignment, a string, a Java expression, a block, a number, a path) is not a
 * command name, and nothing of it is written. A word typed alone is a name whatever it is: a password typed at the prompt
 * by mistake would be logged, as the name of an unknown command.
 */
public final class LoggedLine {

    /** Written in place of what is left out. */
    static final String TRUNCATED = "[truncated...]";
    /** Longest name written; a longer one is cut. */
    private static final int MAX_NAME = 64;

    private LoggedLine() {
    }

    /** The line as it may be logged; empty for a blank line. */
    public static String of(String line) {
        String text = line == null ? "" : line.strip();
        if (text.isEmpty()) {
            return "";
        }
        int start = text.charAt(0) == '^' ? 1 : 0;
        int end = start;
        if (start < text.length() && isNameStart(text.charAt(start))) {
            end = start + 1;
            while (end < text.length() && isNamePart(text.charAt(end))) {
                end++;
            }
        }
        boolean named = end > start && (end == text.length() || isBoundary(text.charAt(end)));
        if (!named) {
            return TRUNCATED;
        }
        boolean cut = end - start > MAX_NAME;
        String name = text.substring(0, cut ? start + MAX_NAME : end);
        return cut || end < text.length() ? name + " " + TRUNCATED : name;
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isNamePart(char c) {
        return Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-' || c == ':';
    }

    /** What may follow a command name: a blank, or a separator of statements or of streams. */
    private static boolean isBoundary(char c) {
        return Character.isWhitespace(c) || c == ';' || c == '|' || c == '&' || c == '>' || c == '<';
    }
}
