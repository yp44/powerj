package io.powerj.fs;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Locale;

/** Wildcard patterns ({@code *.java}, {@code a?.txt}) on file names. */
public final class Globs {

    /**
     * Environment variable that makes wildcards ignore case ({@code true} or {@code 1}; {@code false} or {@code 0}, the
     * default, keeps it). The same rule for the cmdlets and for the arguments of a native program (specification FR-40).
     */
    public static final String IGNORE_CASE_VARIABLE = "POWERJ_GLOB_IGNORE_CASE";

    private Globs() {
    }

    /** {@code true} if the text contains a wildcard {@code *} or {@code ?}. */
    public static boolean isPattern(String text) {
        return text.contains("*") || text.contains("?");
    }

    /**
     * {@code true} if the glob pattern holds a wildcard that a shell expands: {@code *}, {@code ?} or a bracket
     * expression {@code [a-c]} (with its closing bracket). A backslash escapes the next character, so
     * {@code a\*b} holds none. {@link #isPattern} does not see the brackets, nor the escapes: a cmdlet takes
     * {@code [x].txt} as the name of a file.
     */
    public static boolean hasWildcard(String pattern) {
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '*' || c == '?' || c == '[' && closes(pattern, i)) {
                return true;
            }
        }
        return false;
    }

    /** Is the bracket at {@code open} closed by a {@code ]} after at least one character? */
    private static boolean closes(String pattern, int open) {
        for (int i = open + 2; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == ']') {
                return true;
            }
        }
        return false;
    }

    /** The glob pattern that matches exactly this text: {@code a*b} becomes {@code a\*b}. */
    public static String escape(String literal) {
        var escaped = new StringBuilder(literal.length() + 4);
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if ("\\*?[]{}".indexOf(c) >= 0) {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    /** The text that a glob pattern without wildcard spells: {@code a\*b} becomes {@code a*b}. */
    public static String unescape(String pattern) {
        var text = new StringBuilder(pattern.length());
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                c = pattern.charAt(++i);
            }
            text.append(c);
        }
        return text.toString();
    }

    /**
     * Value of {@link #IGNORE_CASE_VARIABLE} as a rule.
     *
     * @param value the value of the variable, {@code null} or blank if it is not set
     * @return {@code true} for {@code true} or {@code 1} (any case), {@code false} for anything unset, {@code false} or
     *         {@code 0}
     * @throws IllegalArgumentException for any other value
     */
    public static boolean ignoreCase(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "true", "1" -> true;
            case "false", "0" -> false;
            default -> throw new IllegalArgumentException(value);
        };
    }

    /**
     * Matcher of a glob pattern, to apply to a file name ({@link Path#getFileName()}). It keeps case: {@code *.TXT} does
     * not match {@code a.txt} (on Windows the matcher of the platform ignores case anyway).
     */
    public static PathMatcher matcher(String pattern) {
        return matcher(pattern, false);
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
