package io.powerj.shell;

import static io.powerj.shell.TestTerminal.CTRL_C;
import static io.powerj.shell.TestTerminal.ENTER;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.BuildInfo;
import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.runtime.Session;
import io.powerj.core.runtime.Supervisor;

/** Perf mode in the shell (FR-76): Alt+P, {@code PJ%} prompt, summary after the line, {@code perf}. */
@Timeout(60)
class PerfModeTest {

    private static final String ALT_P = Perfer.TOGGLE_KEY;
    private static final String ALT_V = Previewer.TOGGLE_KEY;

    @TempDir
    Path tmp;

    private TestTerminal terminal;
    private PreviewLineReader reader;
    private CompletableFuture<Integer> code;
    private Path cwd;
    private Session session;

    @BeforeEach
    void start() throws Exception {
        cwd = Files.createDirectories(tmp.resolve("dev"));
        var home = new PowerJHome(tmp.resolve("home")).createDirectories();
        terminal = TestTerminal.interactive();
        reader = ShellReader.create(terminal.terminal(), home, ShellConfig.defaults());
        session = new Session(tmp, cwd, System.getenv());
        var repl = InteractiveShell.create(reader, new Supervisor(), session, CmdletRegistry.discover());
        terminal.startTyping();
        code = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> code.complete(repl.run(new BuildInfo("0.1.0", Runtime.Version.parse("25")))));
        awaitUntil(() -> reader.isReading());
    }

    @AfterEach
    void stop() throws Exception {
        terminal.close();
    }

    private void exit() throws Exception {
        terminal.type(CTRL_C);
        awaitUntil(() -> reader.isReading() && reader.getBuffer().length() == 0);
        terminal.type("exit 0" + ENTER);
        assertThat(code.get(20, TimeUnit.SECONDS)).isZero();
    }

    private void line(String text) throws Exception {
        terminal.type(text + ENTER);
    }

    @Test
    void altPSwitchesTheModeAndThePromptShowsIt() throws Exception {
        terminal.type(ALT_P);
        awaitUntil(() -> terminal.screen().contains("PJ% " + cwd + "> "));
        terminal.type(ALT_P);
        awaitUntil(() -> terminal.screen().endsWith("PJ " + cwd + "> "));
        exit();
    }

    @Test
    void aMeasuredLinePrintsItsSummary() throws Exception {
        Files.writeString(cwd.resolve("a.txt"), "a");
        terminal.type(ALT_P);
        awaitUntil(() -> terminal.screen().contains("PJ% "));
        line("ls | count");
        awaitUntil(() -> terminal.screen().contains("── perf ·"));
        assertThat(terminal.screen()).contains("stage", "active", "count", "display", "JVM");
        assertThat(session.perfReports()).hasSize(1);
        exit();
    }

    @Test
    void nothingIsMeasuredInNormalMode() throws Exception {
        line("ls | count");
        awaitUntil(() -> terminal.screen().contains("PJ " + cwd + "> ") && terminal.screen().indexOf("1") > 0);
        assertThat(terminal.screen()).doesNotContain("── perf");
        assertThat(session.perfReports()).isEmpty();
        exit();
    }

    @Test
    void perfShowsTheLastLineAfterTheFact() throws Exception {
        terminal.type(ALT_P);
        awaitUntil(() -> terminal.screen().contains("PJ% "));
        line("ls | count");
        awaitUntil(() -> session.perfReports().size() == 1);
        line("perf --view lines");
        awaitUntil(() -> terminal.screen().contains("ls | count") && terminal.screen().contains("bottleneck"));
        assertThat(session.perfReports()).hasSize(1); // perf is not measured itself
        exit();
    }

    @Test
    void perfModeAndPreviewModeExcludeEachOther() throws Exception {
        terminal.type(ALT_V);
        awaitUntil(() -> terminal.screen().contains("PJ? " + cwd + "> "));
        terminal.type(ALT_P); // the screen only receives what changes: the marker alone
        awaitUntil(() -> terminal.screen().contains("PJ%"));
        line("1 + 1");
        awaitUntil(() -> session.perfReports().size() == 1); // perf mode is on, so preview mode is off
        terminal.type(ALT_V);
        awaitUntil(() -> terminal.screen().lastIndexOf("PJ?") > terminal.screen().lastIndexOf("PJ%"));
        line("2 + 2");
        awaitUntil(() -> terminal.screen().contains("4"));
        assertThat(session.perfReports()).hasSize(1); // entering the preview left the perf mode
        exit();
    }

    @Test
    void autoExitEndsTheModeAfterOneLine() throws Exception {
        line("env --set POWERJ_PERF_AUTOEXIT=1"); // in normal mode: not measured
        awaitUntil(() -> reader.isReading() && terminal.screen().endsWith("PJ " + cwd + "> "));
        terminal.type(ALT_P);
        awaitUntil(() -> terminal.screen().contains("PJ%"));
        line("ls | count");
        awaitUntil(() -> session.perfReports().size() == 1);
        awaitUntil(() -> terminal.screen().endsWith("PJ " + cwd + "> ")); // the prompt is the normal one again
        line("ls | count");
        awaitUntil(() -> terminal.screen().lastIndexOf("PJ " + cwd + "> ") > terminal.screen().indexOf("── perf ·") + 20
                && reader.isReading());
        assertThat(session.perfReports()).hasSize(1); // not measured: the mode had ended
        exit();
    }

    private void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition never reached; screen:\n" + terminal.screen());
            }
            Thread.sleep(10);
        }
    }
}
