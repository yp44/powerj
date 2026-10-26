package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class AutomaticVariableTest {

    @Test
    void everyVariableIsFoundByItsName() {
        for (AutomaticVariable variable : AutomaticVariable.values()) {
            assertThat(AutomaticVariable.named(variable.variableName())).contains(variable);
        }
    }

    @Test
    void theNamesAreDistinctAndInDeclarationOrder() {
        assertThat(AutomaticVariable.names()).containsExactly("?", "exit", "last", "pwd", "home", "errors", "perf")
                .doesNotHaveDuplicates();
        assertThat(AutomaticVariable.names()).isEqualTo(
                Arrays.stream(AutomaticVariable.values()).map(AutomaticVariable::variableName).toList());
    }

    @Test
    void aNameThatIsNotAutomaticIsNotFound() {
        assertThat(AutomaticVariable.named("x")).isEmpty();
        assertThat(AutomaticVariable.named("")).isEmpty();
        assertThat(AutomaticVariable.named("EXIT")).isEmpty(); // names are case-sensitive
    }
}
