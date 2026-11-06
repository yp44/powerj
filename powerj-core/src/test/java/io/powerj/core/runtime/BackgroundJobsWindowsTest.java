package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.nativeexec.BackgroundLauncher;
import io.powerj.core.nativeexec.NativeRun;
import io.powerj.fs.Host;

/**
 * Background jobs on Windows (issue #128, FR-75): a job is started by {@link BackgroundLauncher} with
 * {@code CreateProcessW} in a new process group, so that the Ctrl+C of the console does not reach it; a batch file is left
 * to {@link ProcessBuilder}. Real programs ({@code cmd}, {@code ping}, {@code sort}): Windows only. What a real Ctrl+C does
 * is checked in a console of its own by {@code WindowsSpawnTest}.
 */
class BackgroundJobsWindowsTest {

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = new ArrayList<>();
    private Session session;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(Host.windows(), "Windows programs");
        // Windows always has what the launcher needs: a failure here must not hide the tests that follow as skipped.
        assertThat(BackgroundLauncher.available()).as("CreateProcessW through the FFM API").isTrue();
        Files.createDirectories(tmp.resolve("home"));
        Map<String, String> environment = new HashMap<>(System.getenv());
        session = new Session(tmp.resolve("home"), tmp, environment);
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(out, true), errors::add, false), Map.of(),
                CmdletRegistry.of(List.of()));
    }

    @AfterEach
    void stopThePings() {
        ProcessHandle.current().children().filter(p -> p.info().command().orElse("").toLowerCase().endsWith("ping.exe"))
                .forEach(ProcessHandle::destroyForcibly);
    }

    private void run(String line) throws Exception {
        out.getBuffer().setLength(0);
        errors.clear();
        interpreter.execute(line);
    }

    @Test
    void theEndOfAJobIsReportedWithItsExitCode() throws Exception {

        run("cmd /c exit 7 &");
        awaitEnd();

        assertThat(errors).isEmpty();
        assertThat(session.jobs().drainFinished()).singleElement().isEqualTo("[1] done cmd.exe /c exit 7 (exit 7)");
    }

    @Test
    void aJobGivesControlBackAtOnceAndCanBeStopped() throws Exception {

        long start = System.nanoTime();
        run("ping -t 127.0.0.1 &");
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(5));

        assertThat(errors).isEmpty();
        NativeRun last = session.lastNative().orElseThrow();
        assertThat(last.exitCode()).isNull();
        assertThat(session.lastSucceeded()).isTrue();
        assertThat(session.jobs().list()).singleElement().satisfies(job -> {
            assertThat(job.pid()).isEqualTo(last.pid());
            assertThat(job.running()).isTrue();
        });
        ProcessHandle.of(last.pid()).orElseThrow().destroy();
        awaitEnd();
        assertThat(session.jobs().drainFinished()).singleElement().asString().endsWith("(exit 1)");
    }

    @Test
    void redirectionsAndPipelinesWork() throws Exception {
        Files.writeString(tmp.resolve("in.txt"), "b\r\na\r\n");

        run("cmd /c echo hello > out.txt &");
        awaitEnd();
        run("cmd /c type in.txt | sort > sorted.txt &");
        awaitEnd();

        assertThat(errors).isEmpty();
        assertThat(lines(tmp.resolve("out.txt"))).containsExactly("hello");
        assertThat(lines(tmp.resolve("sorted.txt"))).containsExactly("a", "b");
    }

    @Test
    void theJobSeesTheEnvironmentOfTheSessionAndNotTheJvm() throws Exception {
        session.environment().keySet().removeIf(name -> name.equalsIgnoreCase("USERPROFILE"));
        session.environment().put("POWERJ_PROBE", "from-the-session");

        run("cmd /c set POWERJ_PROBE > probe.txt &");
        awaitEnd();
        run("cmd /c set USERPROFILE > inherited.txt &");
        awaitEnd();

        assertThat(lines(tmp.resolve("probe.txt"))).containsExactly("POWERJ_PROBE=from-the-session");
        assertThat(lines(tmp.resolve("inherited.txt"))).isEmpty();
    }

    @Test
    void aBatchFileIsStartedByProcessBuilderAndStillWorks() throws Exception {
        Files.writeString(tmp.resolve("hello.cmd"), "@echo off\r\necho hello %1\r\n");
        String pathKey = session.environment().keySet().stream().filter(name -> name.equalsIgnoreCase("PATH")).findFirst()
                .orElse("PATH");
        session.environment().put(pathKey, tmp + ";" + session.environment().getOrDefault(pathKey, ""));

        run("hello x > batch.txt &");
        awaitEnd();

        assertThat(errors).isEmpty();
        assertThat(lines(tmp.resolve("batch.txt"))).containsExactly("hello x");
    }

    private static List<String> lines(Path file) throws Exception {
        return Files.readAllLines(file, StandardCharsets.ISO_8859_1).stream().map(l -> l.replace("\r", "")).toList();
    }

    /** Waits for the end of the background jobs. */
    private void awaitEnd() throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (session.jobs().anyRunning()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("background job still running");
            }
            Thread.sleep(20);
        }
    }
}
