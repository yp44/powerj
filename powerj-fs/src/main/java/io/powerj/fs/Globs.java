package io.powerj.fs;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Locale;

/** Wildcard patterns ({@code *.java}, {@code a?.txt}) on file names. */
public final class Globs {

    private Globs() {
    }

    /** {@code true} if the text contains a wildcard {@code *} or {@code ?}. */
    public static boolean isPattern(String text) {
        return text.contains("*") || text.contains("?");
    }

    /**
     * Matcher of a glob pattern, case-insensitive on every system: {@code *.TXT} matches {@code a.txt}.
     * Apply it to a file name ({@link Path#getFileName()}).
     */
    public static PathMatcher matcher(String pattern) {
        return matcher(pattern, true);
    }

    /**
     * Matcher of a glob pattern, to apply to a file name ({@link Path#getFileName()}).
     *
     * @param ignoreCase {@code true}: {@code *.TXT} matches {@code a.txt}; {@code false}: only the names written in the
     *                   same case (on Windows the matcher of the platform ignores case anyway). A command that removes or overwrites files should not ignore case where the file
     *                   system does not: {@code rm README*} would also remove {@code readme.md} (review C-22).
     * @throws java.util.regex.PatternSyntaxException if the pattern is not a valid glob (an unclosed {@code [})
     */
    public static PathMatcher matcher(String pattern, boolean ignoreCase) {
        if (!ignoreCase) {
            return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        }
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern.toLowerCase(Locale.ROOT));
        return p -> matcher.matches(Path.of(p.toString().toLowerCase(Locale.ROOT)));
    }
}
