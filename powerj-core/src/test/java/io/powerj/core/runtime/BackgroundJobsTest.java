package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
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
import io.powerj.core.nativeexec.CommandResolver;
import io.powerj.core.nativeexec.NativeEncoding;
import io.powerj.core.nativeexec.NativeRun;
import io.powerj.fs.Host;

/**
 * Background jobs on Linux and macOS (issue #128, FR-75): a job is started by {@link BackgroundLauncher} in a process group
 * of its own, not by {@code sh}, so that the Ctrl+C of the terminal does not reach it; the {@code sh} fallback exists only for
 * a system that cannot, and only when the session allows it. Real programs ({@code sleep}, {@code sh}): Unix only.
 * What happens to a real Ctrl+C is checked on a pseudo-terminal ({@code powerj-core/src/test/prototype/pty-check.py}).
 */
class BackgroundJobsTest {

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = new ArrayList<>();
    private Session session;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() throws Exception {
        assumeFalse(Host.windows(), "Unix commands");
        if (Host.mac()) {
            // macOS 10.15 or later has every function: a failure here must not hide the tests that follow as skipped.
            assertThat(BackgroundLauncher.available()).as("posix_spawn through the FFM API on macOS").isTrue();
        }
        assumeTrue(BackgroundLauncher.available(), "a system with the functions of posix_spawn in its libc");
        session = newSession();
        interpreter = interpreter(new NativeRunner());
    }

    private Session newSession() throws IOException {
        Files.createDirectories(tmp.resolve("home"));
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("HOME", tmp.resolve("home").toString());
        return new Session(tmp.resolve("home"), tmp, environment);
    }

    private Interpreter interpreter(NativeRunner runner) {
        return new Interpreter(session, new ShellIo(new PrintWriter(out, true), errors::add, false), Map.of(),
                CmdletRegistry.of(List.of()), new CommandResolver(), runner);
    }

    @AfterEach
    void stopTheSleepers() {
        ProcessHandle.current().children().filter(p -> p.info().command().orElse("").endsWith("sleep"))
                .forEach(ProcessHandle::destroyForcibly);
    }

    private void run(String line) throws Exception {
        out.getBuffer().setLength(0);
        errors.clear();
        interpreter.execute(line);
    }

    @Test
    void aJobIsTheLeaderOfAProcessGroupOfItsOwn() throws Exception {
        run("sleep 30 &");

        assertThat(errors).isEmpty();
        NativeRun last = session.lastNative().orElseThrow();
        assertThat(last.exitCode()).isNull();
        assertThat(session.lastSucceeded()).isTrue();
        assertThat(processGroup(last.pid())).isEqualTo(last.pid()).isNotEqualTo(processGroup(ProcessHandle.current().pid()));
        assertThat(session.jobs().list()).singleElement().satisfies(job -> {
            assertThat(job.id()).isEqualTo(1);
            assertThat(job.pid()).isEqualTo(last.pid());
            assertThat(job.command()).isEqualTo("sleep 30");
            assertThat(job.running()).isTrue();
        });
    }

    @Test
    void theEndOfAJobIsReportedWithItsExitCode() throws Exception {
        run("sh -c \"exit 7\" &");
        awaitEnd();

        assertThat(session.jobs().drainFinished()).singleElement().isEqualTo("[1] done sh -c \"exit 7\" (exit 7)");
    }

    @Test
    void stoppingAJobGivesTheCodeOfTheSignal() throws Exception {
        run("sleep 30 &");
        ProcessHandle.of(session.lastNative().orElseThrow().pid()).orElseThrow().destroy();
        awaitEnd();

        assertThat(session.jobs().drainFinished()).singleElement().asString().endsWith("(exit 143)");
    }

    @Test
    void theStagesOfAPipelineShareTheGroupOfTheFirstOne() throws Exception {
        run("sleep 30 | sleep 31 &");

        long last = session.lastNative().orElseThrow().pid();
        long group = processGroup(last);
        assertThat(group).isNotEqualTo(last).isNotEqualTo(processGroup(ProcessHandle.current().pid()));
        ProcessHandle first = ProcessHandle.of(group).orElseThrow();
        assertThat(first.isAlive()).isTrue();
        assertThat(first.parent()).contains(ProcessHandle.current());
        assertThat(session.jobs().list()).singleElement()
                .satisfies(job -> assertThat(job.command()).isEqualTo("sleep 30 | sleep 31"));
    }

    @Test
    void redirectionsAndPipelinesWork() throws Exception {
        run("sh -c \"echo dehors; echo erreur >&2\" > out.txt 2> err.txt &");
        awaitEnd();
        run("printf \"b\\na\\n\" | sort > tri.txt &");
        awaitEnd();

        assertThat(errors).isEmpty();
        assertThat(Files.readString(tmp.resolve("out.txt"))).isEqualTo("dehors\n");
        assertThat(Files.readString(tmp.resolve("err.txt"))).isEqualTo("erreur\n");
        assertThat(Files.readString(tmp.resolve("tri.txt"))).as("after the pipeline").isEqualTo("a\nb\n");
        run("printf \"x\\n\" >> tri.txt &");
        awaitEnd();
        assertThat(Files.readString(tmp.resolve("tri.txt"))).as("after >>").isEqualTo("a\nb\nx\n");
    }

    @Test
    void theJobRunsInTheDirectoryAndWithTheEnvironmentOfTheSessionOnly() throws Exception {
        String inherited = System.getenv().keySet().stream()
                .filter(name -> name.matches("[A-Z][A-Z_]*") && !name.equals("PATH") && !name.equals("HOME"))
                .findFirst().orElse(null);
        assumeTrue(inherited != null, "the JVM has an environment variable to leave out");
        session.environment().remove(inherited);
        session.environment().put("POWERJ_PROBE", "from-the-session");

        run("printenv POWERJ_PROBE > probe.txt &");
        awaitEnd();
        run("printenv " + inherited + " > inherited.txt &");
        awaitEnd();
        run("sh -c pwd > cwd.txt &");
        awaitEnd();

        assertThat(Files.readString(tmp.resolve("probe.txt"))).isEqualTo("from-the-session\n");
        assertThat(Files.readString(tmp.resolve("inherited.txt"))).isEmpty();
        assertThat(Files.readString(tmp.resolve("cwd.txt"))).isEqualTo(tmp.toRealPath() + "\n");
    }

    // --- a system that cannot start a job in a group of its own ---

    @Test
    void withoutTheGroupLauncherAndWithoutTheVariableTheJobIsRefusedWithAMessageThatNamesIt() throws Exception {
        interpreter = interpreter(new NativeRunner(new NativeEncoding(), () -> false));

        run("sleep 30 &");

        assertThat(errors).singleElement().asString().contains(BackgroundLauncher.SH_FALLBACK_VARIABLE)
                .contains("posix_spawn");
        assertThat(session.jobs().list()).isEmpty();
    }

    @Test
    void theVariableMustBeTrueToAllowTheShFallback() throws Exception {
        interpreter = interpreter(new NativeRunner(new NativeEncoding(), () -> false));
        session.environment().put(BackgroundLauncher.SH_FALLBACK_VARIABLE, "1");

        run("sleep 30 &");

        assertThat(errors).singleElement().asString().contains(BackgroundLauncher.SH_FALLBACK_VARIABLE);
        assertThat(session.jobs().list()).isEmpty();
    }

    @Test
    void withTheVariableTheFallbackStartsTheJobThroughShInTheGroupOfTheShellAndIgnoringSigint() throws Exception {
        interpreter = interpreter(new NativeRunner(new NativeEncoding(), () -> false));
        session.environment().put(BackgroundLauncher.SH_FALLBACK_VARIABLE, "true");

        run("sleep 30 &");

        assertThat(errors).isEmpty();
        long pid = session.lastNative().orElseThrow().pid();
        assertThat(processGroup(pid)).isEqualTo(processGroup(ProcessHandle.current().pid()));
        ProcessHandle sleep = ProcessHandle.of(pid).orElseThrow();
        // The shell that starts it ignores SIGINT, then becomes sleep: signalled before that it would die, which no user
        // can do within a millisecond of pressing Enter.
        io.powerj.core.support.Waits.until("the shell to have become sleep",
                () -> sleep.info().command().map(command -> command.endsWith("sleep")).orElse(true));
        new ProcessBuilder("kill", "-INT", String.valueOf(pid)).start().waitFor();
        // The one wait of this suite that is a duration on purpose: "the process did NOT die" can only be observed by
        // letting some time pass (a death would be immediate).
        Thread.sleep(300);
        assertThat(sleep.isAlive()).isTrue();
    }

    // --- helpers ---

    /** The process group of a process, from {@code ps}, which Linux and macOS both have. */
    private static long processGroup(long pid) throws IOException, InterruptedException {
        Process ps = new ProcessBuilder("ps", "-o", "pgid=", "-p", Long.toString(pid)).redirectErrorStream(true).start();
        String text = new String(ps.getInputStream().readAllBytes()).strip();
        ps.waitFor();
        return Long.parseLong(text);
    }

    /** Waits for the end of the background jobs. */
    private void awaitEnd() throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (session.jobs().anyRunning()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("background job still running");
            }
            Thread.sleep(20);
        }
    }
}
