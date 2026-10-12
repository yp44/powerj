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
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern.toLowerCase(Locale.ROOT));
        return p -> matcher.matches(Path.of(p.toString().toLowerCase(Locale.ROOT)));
    }
}
