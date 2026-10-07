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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.jline.reader.History;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.BuildInfo;
import io.powerj.core.exec.Session;
import io.powerj.core.exec.Supervisor;

@Timeout(30)
class ReplTest {

    private static final BuildInfo BUILD = new BuildInfo("0.1.0", Runtime.Version.parse("27"));

    @TempDir
    Path tmp;

    private PowerJHome home;
    private String screen;
    private List<String> history;

    private Session session() throws Exception {
        var cwd = java.nio.file.Files.createDirectories(tmp.resolve("dev"));
        return new Session(tmp, cwd, System.getenv());
    }

    /** Lance une session complète en tapant {@code keys} ; renvoie le code retour du shell. */
    private int session(String keys) throws Exception {
        if (home == null) {
            home = new PowerJHome(tmp.resolve("home")).createDirectories();
        }
        try (var terminal = new TestTerminal(keys)) {
            var reader = ShellReader.create(terminal.terminal(), home, ShellConfig.defaults());
            var repl = new Repl(reader, new Supervisor(), session());
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
        assertThat(screen).contains("PowerJ 0.1.0 (Java 27)", "PJ " + tmp.resolve("dev") + "> ");
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
        home = new PowerJHome(tmp.resolve("home")).createDirectories();
        try (var terminal = TestTerminal.interactive()) {
            var reader = ShellReader.create(terminal.terminal(), home, ShellConfig.defaults());
            var repl = new Repl(reader, new Supervisor(), session());
            terminal.startTyping();
            var code = new CompletableFuture<Integer>();
            Thread.ofVirtual().start(() -> code.complete(repl.run(BUILD)));

            // Ctrl+C n'est envoyé qu'une fois la ligne effectivement en cours de saisie.
            terminal.type("abandonnée");
            awaitUntil(() -> reader.isReading() && reader.getBuffer().toString().equals("abandonnée"));
            terminal.type(CTRL_C);
            awaitUntil(() -> reader.isReading() && reader.getBuffer().length() == 0);
            terminal.type("exit 4" + ENTER);

            assertThat(code.get(20, TimeUnit.SECONDS)).isEqualTo(4);
            assertThat(reader.getHistory()).extracting(History.Entry::line).containsExactly("exit 4");
        }
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition jamais atteinte");
            }
            Thread.sleep(10);
        }
    }

    @Test
    void upArrowRecallsPreviousCommands() throws Exception {
        session("essai un" + ENTER + "essai deux" + ENTER + UP + UP + ENTER);

        assertThat(history).containsExactly("essai un", "essai deux", "essai un");
    }

    @Test
    void upArrowOnlyProposesEntriesStartingWithTheTypedPrefix() throws Exception {
        session("liste -r" + ENTER + "statut git" + ENTER + "pwd" + ENTER + "st" + UP + ENTER);

        assertThat(history).containsExactly("liste -r", "statut git", "pwd", "statut git");
    }

    @Test
    void ctrlRSearchesBackwardsInHistory() throws Exception {
        session("bonjour" + ENTER + "liste --filter *.txt" + ENTER + "pwd" + ENTER + CTRL_R + "txt" + ENTER);

        assertThat(history).last().isEqualTo("liste --filter *.txt");
    }

    @Test
    void historyIsPersistedBetweenSessions() throws Exception {
        session("essai un" + ENTER + "essai deux" + ENTER);
        assertThat(Files.readString(home.historyFile())).contains("essai un", "essai deux");

        session(UP + UP + ENTER);

        assertThat(history).containsExactly("essai un", "essai deux", "essai un");
        assertThat(screen).contains("commande inconnue : essai");
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
        session("liste |" + ENTER + "where { $_.dir" + ENTER + "}" + ENTER);

        assertThat(screen).contains(">> ", "pipeline « | » n'est pas encore disponible");
        assertThat(history).containsExactly("liste |\nwhere { $_.dir\n}");
    }
}
