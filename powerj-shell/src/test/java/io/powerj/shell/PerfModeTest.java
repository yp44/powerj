package io.powerj.shell;

import static io.powerj.shell.TestTerminal.CTRL_C;
import static io.powerj.shell.TestTerminal.ENTER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.jline.reader.LineReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.BuildInfo;
import io.powerj.core.exec.CmdletRegistry;
import io.powerj.core.exec.Session;
import io.powerj.core.exec.Supervisor;

/** Perf mode in the shell (FR-76): Alt+P, {@code PJ⏱} prompt, plan below the line, figures after the output. */
@Timeout(60)
class PerfModeTest {

    private static final String ALT_P = PerfMode.TOGGLE_KEY;
    private static final String ALT_V = Previewer.TOGGLE_KEY;

    @TempDir
    Path tmp;

    private TestTerminal terminal;
    private LineReader reader;
    private CompletableFuture<Integer> code;
    private Path cwd;

    @BeforeEach
    void start() throws Exception {
        cwd = Files.createDirectories(tmp.resolve("dev"));
        var home = new PowerJHome(tmp.resolve("home")).createDirectories();
        terminal = TestTerminal.interactive();
        reader = ShellReader.create(terminal.terminal(), home, ShellConfig.defaults());
        var repl = new Repl(reader, new Supervisor(), new Session(tmp, cwd, System.getenv()), CmdletRegistry.discover());
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

    private void type(String keys) throws Exception {
        terminal.type(keys);
        awaitUntil(() -> reader.isReading() && reader.getBuffer().toString().equals(keys));
    }

    @Test
    void altPShowsThePlanWhileTypingAndTheFiguresAfterRunning() throws Exception {
        terminal.type(ALT_P);
        awaitUntil(() -> terminal.screen().contains("PJ⏱ " + cwd + "> "));
        type("ls | where size > 0 | sort name");
        awaitUntil(() -> terminal.screen().contains("Plan · 3 stage(s)"));
        assertThat(terminal.screen()).contains("cmdlet", "own thread", "calling thread");

        terminal.type(ENTER);
        awaitUntil(() -> terminal.screen().contains("Perf · total"));
        assertThat(terminal.screen()).contains("statement 1", "stages run at the same time");
        terminal.type(ALT_P);
        awaitUntil(() -> terminal.screen().endsWith("PJ " + cwd + "> "));
        exit();
    }

    @Test
    void noFiguresOutsidePerfMode() throws Exception {
        terminal.type("\"abc\".length()" + ENTER);
        awaitUntil(() -> terminal.screen().split("PJ " + java.util.regex.Pattern.quote(cwd.toString()), -1).length == 3);
        assertThat(terminal.screen()).doesNotContain("Perf · total");
        exit();
    }

    @Test
    void perfModeAndPreviewModeExcludeEachOther() throws Exception {
        terminal.type(ALT_V);
        awaitUntil(() -> terminal.screen().contains("PJ? " + cwd + "> "));
        Thread.sleep(300);
        terminal.type(ALT_P);
        // the screen only receives what changes: the one character of the prompt that differs
        awaitUntil(() -> terminal.screen().contains("⏱"));
        Thread.sleep(300);
        terminal.type(ALT_V);
        awaitUntil(() -> terminal.screen().lastIndexOf('?') > terminal.screen().lastIndexOf('⏱'));
        exit();
    }

    private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition never reached");
            }
            Thread.sleep(10);
        }
    }
}
