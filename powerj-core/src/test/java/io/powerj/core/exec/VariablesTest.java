package io.powerj.core.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The variables the user defines. */
class VariablesTest {

    private final Variables variables = new Variables();

    @Test
    void aVariableHoldsAValueAndIsReplacedByTheNext() {
        variables.set("x", 1);
        variables.set("x", "two");

        assertThat(variables.get("x")).isEqualTo("two");
    }

    @Test
    void aVariableMayHoldNull() {
        variables.set("x", null);

        assertThat(variables.get("x")).isNull();
        assertThat(variables.names()).contains("x");
    }

    @Test
    void anUnknownVariableIsAnError() {
        assertThatThrownBy(() -> variables.get("nothing")).isInstanceOf(PjException.class)
                .hasMessage("unknown variable: $nothing");
    }

    @Test
    void theNamesAreSorted() {
        variables.set("b", 1);
        variables.set("a", 1);
        variables.set("c", 1);

        assertThat(variables.names()).containsExactly("a", "b", "c");
    }

    @Test
    void theNamesReturnedAreACopy() {
        variables.set("a", 1);
        var names = variables.names();

        variables.set("b", 1);

        assertThat(names).containsExactly("a");
    }

    @Test
    void debugIsOnlyTrueForTheBooleanTrue() {
        assertThat(variables.debug()).isFalse();

        variables.set("debug", "true");
        assertThat(variables.debug()).isFalse();

        variables.set("debug", true);
        assertThat(variables.debug()).isTrue();

        variables.set("debug", false);
        assertThat(variables.debug()).isFalse();
    }
}
