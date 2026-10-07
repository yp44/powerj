package io.powerj.shell;

import static io.powerj.shell.TestTerminal.CTRL_C;
import static io.powerj.shell.TestTerminal.CTRL_D;
import static io.powerj.shell.TestTerminal.CTRL_R;
import static io.powerj.shell.TestTerminal.ENTER;
import static io.powerj.shell.TestTerminal.UP;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.jline.reader.History;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.BuildInfo;
import io.powerj.core.exec.Supervisor;

@Timeout(30)
class ReplTest {

    private static final BuildInfo BUILD = new BuildInfo("0.1.0", Runtime.Version.parse("27"));

    @TempDir
    Path tmp;

    private PowerJHome home;
    private String screen;
    private List<String> history;

    /** Lance une session complète en tapant {@code keys} ; renvoie le code retour du shell. */
    private int session(String keys) throws Exception {
        if (home == null) {
            home = new PowerJHome(tmp.resolve("home")).createDirectories();
        }
        try (var terminal = new TestTerminal(keys)) {
            var reader = ShellReader.create(terminal.terminal(), home, ShellConfig.defaults());
            var repl = new Repl(reader, new Supervisor(), () -> Path.of("C:\\dev"));
            terminal.startTyping();
            int code = repl.run(BUILD);
            reader.getHistory().save();
            screen = terminal.screen();
            history = new ArrayList<>();
            for (History.Entry e : reader.getHistory()) {
                history.add(e.line());
            }
            return code;
        }
    }

    @Test
    void bannerPromptAndExitCode() throws Exception {
        assertThat(session("exit 3" + ENTER)).isEqualTo(3);
        assertThat(screen).contains("PowerJ 0.1.0 (Java 27)", "PJ C:\\dev> ");
    }

    @Test
    void unknownCommandIsReportedAndTheShellContinues() throws Exception {
        assertThat(session("bonjour" + ENTER + "exit 2" + ENTER)).isEqualTo(2);
        assertThat(screen).contains("commande inconnue : bonjour");
    }

    @Test
    void ctrlDOnEmptyLineQuits() throws Exception {
        assertThat(session("bonjour" + ENTER + CTRL_D)).isZero();
        assertThat(history).containsExactly("bonjour");
    }

    @Test
    void endOfInputQuits() throws Exception {
        assertThat(session("bonjour" + ENTER)).isZero();
    }

    @Test
    void ctrlCClearsTheLineBeingTyped() throws Exception {
        assertThat(session("abandonnée" + CTRL_C + "exit 4" + ENTER)).isEqualTo(4);
        assertThat(history).containsExactly("exit 4");
    }

    @Test
    void upArrowRecallsPreviousCommands() throws Exception {
        session("test un" + ENTER + "test deux" + ENTER + UP + UP + ENTER);

        assertThat(history).containsExactly("test un", "test deux", "test un");
    }

    @Test
    void upArrowOnlyProposesEntriesStartingWithTheTypedPrefix() throws Exception {
        session("ls -r" + ENTER + "git status" + ENTER + "pwd" + ENTER + "gi" + UP + ENTER);

        assertThat(history).containsExactly("ls -r", "git status", "pwd", "git status");
    }

    @Test
    void ctrlRSearchesBackwardsInHistory() throws Exception {
        session("bonjour" + ENTER + "ls --filter *.txt" + ENTER + "pwd" + ENTER + CTRL_R + "txt" + ENTER);

        assertThat(history).last().isEqualTo("ls --filter *.txt");
    }

    @Test
    void historyIsPersistedBetweenSessions() throws Exception {
        session("test un" + ENTER + "test deux" + ENTER);
        assertThat(Files.readString(home.historyFile())).contains("test un", "test deux");

        session(UP + UP + ENTER);

        assertThat(history).containsExactly("test un", "test deux", "test un");
        assertThat(screen).contains("commande inconnue : test");
    }

    @Test
    void consecutiveDuplicatesAndLinesStartingWithASpaceAreNotRecorded() throws Exception {
        session("a" + ENTER + "a" + ENTER + " secret" + ENTER + "b" + ENTER);

        assertThat(history).containsExactly("a", "b");
    }

    @Test
    void historyCommandListsNumberedEntries() throws Exception {
        session("alpha" + ENTER + "beta" + ENTER + "history" + ENTER);

        assertThat(screen).contains("    1  alpha", "    2  beta", "    3  history");
    }

    @Test
    void historyClearEmptiesTheHistory() throws Exception {
        session("alpha" + ENTER + "history --clear" + ENTER);

        assertThat(history).isEmpty();
    }

    @Test
    void bangBangRunsThePreviousCommandAgain() throws Exception {
        session("alpha" + ENTER + "beta" + ENTER + "!!" + ENTER);

        assertThat(history).containsExactly("alpha", "beta");  // doublon consécutif ignoré
        assertThat(screen.split("commande inconnue : beta", -1)).hasSize(3);
    }

    @Test
    void bangNumberAndBangPrefixExpandFromHistory() throws Exception {
        session("alpha" + ENTER + "beta" + ENTER + "!1" + ENTER + "!be" + ENTER);

        assertThat(history).containsExactly("alpha", "beta", "alpha", "beta");
    }

    @Test
    void unknownHistoryEventIsReported() throws Exception {
        session("alpha" + ENTER + "!zzz" + ENTER);

        assertThat(screen).contains("historique : aucune commande ne correspond à !zzz");
    }

    @Test
    void incompleteLineContinuesOnTheNextOne() throws Exception {
        session("ls |" + ENTER + "where { $_.dir" + ENTER + "}" + ENTER);

        assertThat(screen).contains(">> ", "commande inconnue : ls");
        assertThat(history).containsExactly("ls |\nwhere { $_.dir\n}");
    }
}
