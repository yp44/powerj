package io.powerj.fs;

/**
 * A word of a command line that the quotes or a variable made literal where it holds a wildcard: what the shell hands to
 * a cmdlet instead of the plain text when the two would not mean the same (specification FR-40, audit v2 R-09).
 * {@code rm "a?.txt"} gives {@code text} {@code a?.txt} and {@code pattern} {@code a\?.txt}: a cmdlet that expands
 * wildcards reads the pattern, so that only the unquoted wildcards expand, and takes the text of a pattern that holds none.
 * Its string form is the text, so that code that does not know it reads the word as it always did.
 *
 * @param text    what the word says
 * @param pattern the glob pattern of the word (a backslash escapes the next character): the quoted text is escaped, the
 *                unquoted text is not
 */
public record GlobArgument(String text, String pattern) {

    /** {@code true} if the word still holds a wildcard that expands (an unquoted one). */
    public boolean hasWildcard() {
        return Globs.hasWildcard(pattern);
    }

    @Override
    public String toString() {
        return text;
    }
}
