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
    private Path perfDir;

    @BeforeEach
    void start() throws Exception {
        cwd = Files.createDirectories(tmp.resolve("dev"));
        var home = new PowerJHome(tmp.resolve("home")).createDirectories();
        terminal = TestTerminal.interactive();
        reader = ShellReader.create(terminal.terminal(), home, ShellConfig.defaults());
        var session = new Session(tmp, cwd, System.getenv());
        session.configDirectory(home.dir());
        perfDir = home.dir().resolve("perf");
        var repl = new Repl(reader, new Supervisor(), session, CmdletRegistry.discover());
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

    private long recordings() throws Exception {
        if (!Files.exists(perfDir)) {
            return 0;
        }
        try (var files = Files.list(perfDir)) {
            return files.count();
        }
    }

    private void run(String line, String expected) throws Exception {
        int before = terminal.screen().length();
        terminal.type(line + ENTER);
        awaitUntil(() -> terminal.screen().substring(before).contains(expected));
        awaitUntil(() -> reader.isReading() && reader.getBuffer().length() == 0);
    }

    @Test
    void theFlightRecorderSummaryFollowsTheReportAndPerfGivesTheDetail() throws Exception {
        terminal.type(ALT_P);
        awaitUntil(() -> terminal.screen().contains("PJ⏱ " + cwd + "> "));
        Files.writeString(cwd.resolve("hello.txt"), "hello");
        run("ls | where size > 0 | count", "jfr · cpu");
        assertThat(terminal.screen()).contains("Perf · total", "alloc", "gc ", "wait ");

        run("perf --stages", "ls");
        assertThat(terminal.screen()).contains("samples", "allocated");
        run("perf --gc", "pause");
        run("perf --bogus", "unknown option '--bogus'");
        exit();
    }

    @Test
    void theNumberOfRecordingsKeptFollowsTheEnvironmentVariable() throws Exception {
        terminal.type(ALT_P);
        awaitUntil(() -> terminal.screen().contains("PJ⏱ " + cwd + "> "));
        run("\"a\".length()", "jfr · cpu");
        assertThat(recordings()).isZero(); // POWERJ_PERF_KEEP unset: deleted after the analysis

        run("env --set POWERJ_PERF_KEEP=2", "jfr · cpu");
        assertThat(recordings()).isEqualTo(1);
        Thread.sleep(5);
        run("\"b\".length()", "jfr · cpu");
        Thread.sleep(5);
        run("\"c\".length()", "jfr · cpu");
        assertThat(recordings()).isEqualTo(2);

        run("env --set POWERJ_PERF_KEEP=0", "jfr · cpu");
        assertThat(recordings()).isZero();
        exit();
    }

    @Test
    void theSummaryKeepsItsColumnsApartAndTheSamplingPeriodComesFromTheEnvironment() throws Exception {
        terminal.type(ALT_P);
        awaitUntil(() -> terminal.screen().contains("PJ⏱ " + cwd + "> "));
        run("env --set POWERJ_PERF_PERIOD=20", "jfr · cpu");
        run("\"a\".length()", "at 20.0 ms");
        run("env --set POWERJ_PERF_PERIOD=oops", "jfr · cpu");
        run("\"a\".length()", "at 1.0 ms");
        // a stage line never runs its figures into the hot methods
        assertThat(terminal.screen()).doesNotContainPattern("(µs|ms|B|KB|MB)(hot|chaud)");
        exit();
    }

    @Test
    void noRecordingOutsidePerfMode() throws Exception {
        run("\"abc\".length()", "3");
        assertThat(recordings()).isZero();
        assertThat(terminal.screen()).doesNotContain("jfr · cpu");
        run("perf", "no profile yet");
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
