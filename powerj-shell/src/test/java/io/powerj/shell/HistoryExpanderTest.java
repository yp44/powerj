package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.jline.reader.History;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryExpanderTest {

    @TempDir
    Path tmp;

    private final HistoryExpander expander = new HistoryExpander();
    private TestTerminal terminal;
    private History history;

    @BeforeEach
    void setUp() throws Exception {
        terminal = new TestTerminal("");
        var home = new PowerJHome(tmp).createDirectories();
        history = ShellReader.create(terminal.terminal(), home, ShellConfig.defaults()).getHistory();
        history.add("ls -r");
        history.add("git status");
        history.add("pwd");
    }

    @Test
    void linesWithoutLeadingBangAreUnchanged() {
        assertThat(expander.expandHistory(history, "ls | where { !$_.dir }")).isEqualTo("ls | where { !$_.dir }");
        assertThat(expander.expandHistory(history, "! seul")).isEqualTo("! seul");
    }

    @Test
    void bangBangIsTheLastCommandAndKeepsTheRestOfTheLine() {
        assertThat(expander.expandHistory(history, "!!")).isEqualTo("pwd");
        assertThat(expander.expandHistory(history, "!! | where")).isEqualTo("pwd | where");
    }

    @Test
    void bangNumberUsesTheNumberShownByHistory() {
        assertThat(expander.expandHistory(history, "!1")).isEqualTo("ls -r");
        assertThat(expander.expandHistory(history, "!3")).isEqualTo("pwd");
    }

    @Test
    void bangPrefixIsTheLatestCommandStartingWithIt() {
        assertThat(expander.expandHistory(history, "!gi")).isEqualTo("git status");
    }

    @Test
    void missingEventIsAnError() {
        assertThatThrownBy(() -> expander.expandHistory(history, "!9")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> expander.expandHistory(history, "!zzz")).isInstanceOf(IllegalArgumentException.class);
    }
}
