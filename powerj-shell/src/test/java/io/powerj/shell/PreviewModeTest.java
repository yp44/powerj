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

/** Preview mode in the shell (FR-66): Alt+V, {@code PJ?} prompt, results below the line. */
@Timeout(60)
class PreviewModeTest {

    private static final String ALT_V = Previewer.TOGGLE_KEY;
    private static final String HOME = "\001"; // Ctrl+A: start of the line

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
    void altVSwitchesTheModeAndThePromptShowsIt() throws Exception {
        terminal.type(ALT_V);
        awaitUntil(() -> terminal.screen().contains("PJ? " + cwd + "> "));
        type("\"abc\".toUpperCase()");
        awaitUntil(() -> terminal.screen().contains("String · 1"));
        assertThat(terminal.screen()).contains("ABC");

        terminal.type(ENTER); // executed normally; the mode stays
        awaitUntil(() -> terminal.screen().split("PJ\\? ", -1).length == 3);
        terminal.type(ALT_V);
        awaitUntil(() -> terminal.screen().endsWith("PJ " + cwd + "> "));
        exit();
    }

    @Test
    void thePreviewFollowsTheStageUnderTheCursor() throws Exception {
        terminal.type(ALT_V);
        type("java.util.List.of(\"alpha1\", \"beta2\") | where { s -> s.endsWith(\"2\") }");
        awaitUntil(() -> terminal.screen().contains("String · 1"));
        int before = terminal.screen().length();
        terminal.type(HOME); // the first stage alone: both values (the screen only receives what changes)
        awaitUntil(() -> terminal.screen().substring(before).contains("alpha1"));
        exit();
    }

    @Test
    void aNativeCommandStopsThePreviewAndNothingRunsBeforeEnter() throws Exception {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "sh");
        terminal.type(ALT_V);
        type("ls | sh -c \"touch made.txt\"");
        awaitUntil(() -> terminal.screen().contains("preview off: native command sh"));
        assertThat(cwd.resolve("made.txt")).doesNotExist();
        terminal.type(ENTER);
        awaitUntil(() -> Files.exists(cwd.resolve("made.txt")));
        exit();
    }

    @Test
    void cmdletResultsAreShownAsATable() throws Exception {
        Files.writeString(cwd.resolve("hello.txt"), "hello");
        terminal.type(ALT_V);
        type("ls");
        awaitUntil(() -> terminal.screen().contains("FileEntry · 1"));
        assertThat(terminal.screen()).contains("hello.txt");
        exit();
    }

    @Test
    void selectRowsAreShownAsATable() throws Exception {
        Files.writeString(cwd.resolve("hello.txt"), "hello");
        terminal.type(ALT_V);
        type("ls | select file=name kb={ $_.size / 1kb }");
        awaitUntil(() -> terminal.screen().contains("Row · 1"));
        assertThat(terminal.screen()).contains("file", "kb", "hello.txt");
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
