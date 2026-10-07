package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CommandTest {

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t", "\n"})
    void blankLineIsEmpty(String line) {
        assertThat(Command.parse(line)).isEqualTo(new Command.Empty());
    }

    @Test
    void exitWithOrWithoutCode() {
        assertThat(Command.parse("exit")).isEqualTo(new Command.Exit(0));
        assertThat(Command.parse("  exit 3 ")).isEqualTo(new Command.Exit(3));
    }

    @Test
    void invalidExit() {
        assertThat(Command.parse("exit trois"))
                .isEqualTo(new Command.Invalid("exit : code retour invalide 'trois'"));
        assertThat(Command.parse("exit 1 2"))
                .isEqualTo(new Command.Invalid("exit : un seul argument attendu"));
    }

    @Test
    void historyListsOrClears() {
        assertThat(Command.parse("history")).isEqualTo(new Command.History(false));
        assertThat(Command.parse("history --clear")).isEqualTo(new Command.History(true));
        assertThat(Command.parse("history -x")).isInstanceOf(Command.Invalid.class);
    }

    @Test
    void unexpandedHistoryEventIsReported() {
        assertThat(Command.parse("!zzz"))
                .isEqualTo(new Command.Invalid("historique : aucune commande ne correspond à !zzz"));
    }

    @Test
    void anythingElseIsUnknown() {
        assertThat(Command.parse("ls -r")).isEqualTo(new Command.Unknown("ls"));
        assertThat(Command.parse("ls |\nwhere")).isEqualTo(new Command.Unknown("ls"));
        assertThat(Command.parse("exiting")).isEqualTo(new Command.Unknown("exiting"));
    }
}
