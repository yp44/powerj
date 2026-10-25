package io.powerj.architecture;

import java.util.regex.Pattern;

import com.tngtech.archunit.library.freeze.ViolationLineMatcher;

/**
 * Tells whether a violation of the store is the same as one found now. ArchUnit's default matcher already ignores the
 * line numbers and the numbers of anonymous classes; this one also ignores
 * <ul>
 *   <li>the parameters of the method a dependency comes from: {@code background(Pipeline, Optional, Optional)} becoming
 *       {@code background(Pipeline, Redirections)} is the same method in the same cycle;</li>
 *   <li>the count of the cycle report, "(213 further dependencies have been omitted...)", which changes whenever code
 *       moves inside a cycle that is already there.</li>
 * </ul>
 * Without them each step of a refactoring rewrote the store for nothing. A dependency from another class or method is
 * still a new violation.
 */
public final class StableViolationLineMatcher implements ViolationLineMatcher {

    private static final Pattern LINE_NUMBER = Pattern.compile(":\\d+");
    private static final Pattern ANONYMOUS_CLASS = Pattern.compile("\\$\\d+");
    private static final Pattern OMITTED_COUNT = Pattern.compile("\\(\\d+ further dependencies");
    private static final Pattern MEMBER_PARAMETERS = Pattern.compile("\\([^()]*\\)>");

    @Override
    public boolean matches(String lineFromFirstViolation, String lineFromSecondViolation) {
        return normalized(lineFromFirstViolation).equals(normalized(lineFromSecondViolation));
    }

    static String normalized(String line) {
        String text = LINE_NUMBER.matcher(line).replaceAll(":#");
        text = ANONYMOUS_CLASS.matcher(text).replaceAll("\\$#");
        text = OMITTED_COUNT.matcher(text).replaceAll("(# further dependencies");
        return MEMBER_PARAMETERS.matcher(text).replaceAll("(...)>");
    }
}
