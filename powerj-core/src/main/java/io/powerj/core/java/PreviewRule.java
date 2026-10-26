package io.powerj.core.java;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.util.Set;
import java.util.function.Predicate;

/**
 * One rule of {@link PreviewPolicy}: for a method or a constructor, it allows it, refuses it, or has no opinion and
 * leaves the decision to the next rule. The first rule with an opinion decides, so the rules are read in order, the
 * refusals that nothing can overturn first.
 */
@FunctionalInterface
interface PreviewRule {

    enum Verdict { ALLOW, REFUSE, ABSTAIN }

    Verdict judge(Executable executable);

    /** {@code verdict} for what {@code matches}, no opinion for the rest. */
    static PreviewRule when(Predicate<Executable> matches, Verdict verdict) {
        return executable -> matches.test(executable) ? verdict : Verdict.ABSTAIN;
    }

    /** A constructor is allowed when its class is in {@code allowed}, refused otherwise; a method is not its concern. */
    static PreviewRule constructorsOf(Set<Class<?>> allowed) {
        return executable -> !(executable instanceof Constructor<?>) ? Verdict.ABSTAIN
                : allowed.contains(executable.getDeclaringClass()) ? Verdict.ALLOW : Verdict.REFUSE;
    }

    /** What {@code type} itself declares is allowed when its name satisfies {@code allowed}, refused otherwise. */
    static PreviewRule decidesFor(Class<?> type, Predicate<String> allowed) {
        return executable -> executable.getDeclaringClass() != type ? Verdict.ABSTAIN
                : allowed.test(executable.getName()) ? Verdict.ALLOW : Verdict.REFUSE;
    }
}
