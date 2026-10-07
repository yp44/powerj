package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CommandTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    void blankLineIsEmpty(String line) {
        assertThat(Command.parse(line)).isEqualTo(new Command.Empty());
    }

    @Test
    void exitWithoutCodeExitsWithZero() {
        assertThat(Command.parse("exit")).isEqualTo(new Command.Exit(0));
        assertThat(Command.parse("  exit  ")).isEqualTo(new Command.Exit(0));
    }

    @Test
    void exitWithCodeExitsWithThatCode() {
        assertThat(Command.parse("exit 3")).isEqualTo(new Command.Exit(3));
    }

    @Test
    void exitWithInvalidCodeIsInvalid() {
        assertThat(Command.parse("exit trois"))
                .isEqualTo(new Command.Invalid("exit : code retour invalide 'trois'"));
        assertThat(Command.parse("exit 1 2"))
                .isEqualTo(new Command.Invalid("exit : un seul argument attendu"));
    }

    @Test
    void anythingElseIsUnknown() {
        assertThat(Command.parse("ls -r")).isEqualTo(new Command.Unknown("ls"));
        assertThat(Command.parse("exiting")).isEqualTo(new Command.Unknown("exiting"));
    }
}
