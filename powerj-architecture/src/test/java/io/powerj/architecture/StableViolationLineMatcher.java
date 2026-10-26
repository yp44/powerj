package io.powerj.architecture;

import java.util.regex.Pattern;

import com.tngtech.archunit.library.freeze.ViolationLineMatcher;

/**
 * Tells whether a violation of the store is the same as one found now. ArchUnit's default matcher already ignores the
 * line numbers and the numbers of anonymous classes; this one also ignores the parameters of the method a dependency
 * comes from: {@code background(Pipeline, Optional, Optional)} becoming {@code background(Pipeline, Redirections)} is
 * the same method making the same dependency.
 * <p>
 * Without it each refactoring of a signature rewrote the store for nothing. A dependency from another class or method is
 * still a new violation.
 */
public final class StableViolationLineMatcher implements ViolationLineMatcher {

    private static final Pattern LINE_NUMBER = Pattern.compile(":\\d+");
    private static final Pattern ANONYMOUS_CLASS = Pattern.compile("\\$\\d+");
    private static final Pattern MEMBER_PARAMETERS = Pattern.compile("\\([^()]*\\)>");

    @Override
    public boolean matches(String lineFromFirstViolation, String lineFromSecondViolation) {
        return normalized(lineFromFirstViolation).equals(normalized(lineFromSecondViolation));
    }

    static String normalized(String line) {
        String text = LINE_NUMBER.matcher(line).replaceAll(":#");
        text = ANONYMOUS_CLASS.matcher(text).replaceAll("\\$#");
        return MEMBER_PARAMETERS.matcher(text).replaceAll("(...)>");
    }
}
