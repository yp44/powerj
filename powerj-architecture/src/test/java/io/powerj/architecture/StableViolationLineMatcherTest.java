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

    private static String cycle(String... dependencies) {
        return "Cycle detected: Slice exec -> \n                Slice lang -> \n                Slice exec\n"
                + "  1. Dependencies of Slice exec\n" + String.join("\n", dependencies)
                + "\n  2. Dependencies of Slice lang\n"
                + "    - Class <io.powerj.core.lang.SyntaxException> extends class <io.powerj.core.exec.PjException> in (SyntaxException.java:0)";
    }

    private static final String CAPTURE = "    - Method <io.powerj.core.exec.Interpreter.capture(io.powerj.core.lang.Ast$Pipeline)> "
            + "has parameter of type <io.powerj.core.lang.Ast$Pipeline> in (Interpreter.java:0)";
    private static final String ARGUMENTS = "    - Method <io.powerj.core.exec.Evaluator.arguments(java.util.List)> has generic parameter type "
            + "<java.util.List<io.powerj.core.lang.Ast$Argument>> with type argument depending on <io.powerj.core.lang.Ast$Argument> in (Evaluator.java:0)";
    private static final String OMITTED = "    (213 further dependencies have been omitted...)";

    @Test
    void whichExecClassesUseLangIsNotCompared() {
        // twenty dependencies are listed, by name: a method moved to another class shifts the list
        assertThat(matcher.matches(cycle(CAPTURE, OMITTED), cycle(ARGUMENTS, OMITTED))).isTrue();
        assertThat(matcher.matches(cycle(CAPTURE, ARGUMENTS, OMITTED), cycle(ARGUMENTS, OMITTED))).isTrue();
    }

    @Test
    void aDependencyOfLangOnExecStaysCompared() {
        String other = cycle(CAPTURE, OMITTED).replace("SyntaxException", "Parser");

        assertThat(matcher.matches(cycle(CAPTURE, OMITTED), cycle(CAPTURE, OMITTED))).isTrue();
        assertThat(matcher.matches(cycle(CAPTURE, OMITTED), other)).isFalse();
        assertThat(matcher.matches(cycle(CAPTURE, OMITTED), cycle(CAPTURE, OMITTED) + "\n    - Class <io.powerj.core.lang.Lexer> extends class <io.powerj.core.exec.PjException> in (Lexer.java:0)"))
                .isFalse();
    }

    @Test
    void anotherCycleIsAnotherViolation() {
        assertThat(matcher.matches(cycle(CAPTURE, OMITTED), cycle(CAPTURE, OMITTED).replace("Slice lang", "Slice perf")))
                .isFalse();
    }

    @Test
    void aLineOfExecThatDoesNotTouchLangStaysCompared() {
        assertThat(matcher.matches("    - Method <io.powerj.core.exec.A.m()> calls <io.powerj.core.exec.B.n()> in (A.java:3)",
                "    - Method <io.powerj.core.exec.A.m()> calls <io.powerj.core.exec.C.n()> in (A.java:3)")).isFalse();
    }
}
