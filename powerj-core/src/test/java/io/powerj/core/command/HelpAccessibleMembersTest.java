package io.powerj.core.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.powerj.core.java.JavaClasses;

/**
 * {@code help} and completion offer what the shell can call, and nothing else (audit v2, B-08): a field declared by a class
 * that is not public is reached through {@code getFields()} but refused when it is read.
 */
class HelpAccessibleMembersTest {

    static class Hidden {
        public int hiddenField = 1;
    }

    public static class Visible extends Hidden {
        public int visibleField = 2;
    }

    @Test
    void aFieldOfAnInaccessibleSuperclassIsNotInTheHelpOfTheClass() {
        String text = Help.javaClass(Visible.class).stream().map(Object::toString).collect(Collectors.joining("\n"));

        assertThat(text).contains("visibleField").doesNotContain("hiddenField");
    }

    @Test
    void theRuleIsTheOneThatTheCallsUse() throws Exception {
        assertThat(JavaClasses.callable(Visible.class.getField("visibleField"))).isTrue();
        assertThat(JavaClasses.callable(Visible.class.getField("hiddenField"))).isFalse();
        // a static member of the Java library is callable, an instance member of a public JDK type too
        assertThat(JavaClasses.callable(Math.class.getMethod("max", int.class, int.class))).isTrue();
        assertThat(JavaClasses.callable(List.class.getMethod("size"))).isTrue();
    }
}
