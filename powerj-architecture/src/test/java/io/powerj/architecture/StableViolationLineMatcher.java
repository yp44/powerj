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
 *       moves inside a cycle that is already there;</li>
 *   <li>which classes of {@code core.exec} depend on {@code core.lang}. A cycle report lists twenty of the
 *       dependencies of each side, sorted by name, so moving a method from a class to a new one changes which twenty
 *       are shown. That direction is the legitimate one (the interpreter uses the syntax tree): the dependency that
 *       closes the cycle is {@code lang} reaching back into {@code exec}, and those lines stay compared one by one.</li>
 * </ul>
 * Without them each step of a refactoring rewrote the store for nothing. A dependency from another class or method is
 * still a new violation, and so is a new dependency of {@code lang} on {@code exec}.
 */
public final class StableViolationLineMatcher implements ViolationLineMatcher {

    private static final Pattern LINE_NUMBER = Pattern.compile(":\\d+");
    private static final Pattern ANONYMOUS_CLASS = Pattern.compile("\\$\\d+");
    private static final Pattern OMITTED_COUNT = Pattern.compile("\\(\\d+ further dependencies");
    private static final Pattern MEMBER_PARAMETERS = Pattern.compile("\\([^()]*\\)>");
    /**
     * The dependency lines whose source is in {@code exec} and that mention {@code lang}: the allowed direction. The
     * whole violation, lines included, reaches the matcher as one string.
     */
    private static final Pattern EXEC_USES_LANG = Pattern.compile(
            "(?m)(^[ \\t]*- \\w+ <io\\.powerj\\.core\\.exec\\.[^\\n]*io\\.powerj\\.core\\.lang\\.[^\\n]*(\\n|$))+");

    @Override
    public boolean matches(String lineFromFirstViolation, String lineFromSecondViolation) {
        return normalized(lineFromFirstViolation).equals(normalized(lineFromSecondViolation));
    }

    static String normalized(String line) {
        String text = EXEC_USES_LANG.matcher(line).replaceAll("    (exec uses lang)\n");
        text = LINE_NUMBER.matcher(text).replaceAll(":#");
        text = ANONYMOUS_CLASS.matcher(text).replaceAll("\\$#");
        text = OMITTED_COUNT.matcher(text).replaceAll("(# further dependencies");
        return MEMBER_PARAMETERS.matcher(text).replaceAll("(...)>");
    }
}
