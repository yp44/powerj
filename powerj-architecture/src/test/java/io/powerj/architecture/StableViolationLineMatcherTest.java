package io.powerj.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StableViolationLineMatcherTest {

    private final StableViolationLineMatcher matcher = new StableViolationLineMatcher();

    @Test
    void theCountOfOmittedDependenciesIsNotCompared() {
        assertThat(matcher.matches("A -> B\\n    (219 further dependencies have been omitted...)",
                "A -> B\\n    (213 further dependencies have been omitted...)")).isTrue();
    }

    @Test
    void lineNumbersAndAnonymousClassesAreNotCompared() {
        assertThat(matcher.matches("Method <a.B.m()> calls <a.C.n()> in (B.java:42)",
                "Method <a.B.m()> calls <a.C.n()> in (B.java:57)")).isTrue();
        assertThat(matcher.matches("Class <a.B$1> depends on <a.C>", "Class <a.B$7> depends on <a.C>")).isTrue();
    }

    @Test
    void theParametersOfTheMethodAreNotCompared() {
        assertThat(matcher.matches(
                "Method <a.B.run(a.lang.Ast$Pipeline, java.util.Optional, java.util.Optional)> has parameter of type <a.lang.Ast$Pipeline> in (B.java:0)",
                "Method <a.B.run(a.lang.Ast$Pipeline, a.exec.Redirections)> has parameter of type <a.lang.Ast$Pipeline> in (B.java:0)"))
                .isTrue();
        assertThat(matcher.matches("Method <a.B.run()> calls <a.C.n()>", "Method <a.B.run(int)> calls <a.C.n()>")).isTrue();
    }

    @Test
    void aDifferentMethodOrClassIsAnotherViolation() {
        assertThat(matcher.matches("Method <a.B.run(int)> has parameter of type <a.lang.X>",
                "Method <a.B.other(int)> has parameter of type <a.lang.X>")).isFalse();
        assertThat(matcher.matches("Method <a.B.run(int)> has parameter of type <a.lang.X>",
                "Method <a.B.run(int)> has parameter of type <a.lang.Y>")).isFalse();
    }

    @Test
    void aDifferentDependencyIsAnotherViolation() {
        assertThat(matcher.matches("Method <a.B.m()> calls <a.C.n()> in (B.java:42)",
                "Method <a.B.m()> calls <a.D.n()> in (B.java:42)")).isFalse();
        assertThat(matcher.matches("A -> B", "A -> C")).isFalse();
    }

    @Test
    void otherNumbersStayCompared() {
        assertThat(matcher.matches("Field <a.B.x2> is used", "Field <a.B.x3> is used")).isFalse();
    }
}
