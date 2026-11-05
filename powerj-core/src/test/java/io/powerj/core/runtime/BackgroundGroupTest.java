package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
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
import io.powerj.core.nativeexec.GroupLauncher;
import io.powerj.core.nativeexec.NativeRun;

/**
 * The experiment of issue #128: with {@code POWERJ_BACKGROUND=group} in the session, a background job is started in a
 * process group of its own, by {@link GroupLauncher} and not by {@code sh}. Real programs ({@code sleep}, {@code sh}):
 * Linux only. What happens to Ctrl+C is checked on a pseudo-terminal ({@code powerj-core/src/test/prototype/pty-check.py}).
 */
class BackgroundGroupTest {

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = new ArrayList<>();
    private Session session;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(GroupLauncher.available(), "Linux, with the functions of posix_spawn in the libc");
        Files.createDirectories(tmp.resolve("home"));
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("HOME", tmp.resolve("home").toString());
        environment.put(GroupLauncher.VARIABLE, GroupLauncher.GROUP);
        session = new Session(tmp.resolve("home"), tmp, environment);
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(out, true), errors::add, false), Map.of(),
                CmdletRegistry.of(List.of()));
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
    void withoutTheVariableTheJobStaysInTheGroupOfTheShell() throws Exception {
        session.environment().remove(GroupLauncher.VARIABLE);

        run("sleep 30 &");

        long pid = session.lastNative().orElseThrow().pid();
        assertThat(processGroup(pid)).isEqualTo(processGroup(ProcessHandle.current().pid()));
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
        assertThat(session.jobs().list()).singleElement().satisfies(job -> assertThat(job.command()).isEqualTo("sleep 30 | sleep 31"));
    }

    @Test
    void redirectionsAndPipelinesWorkAsWithoutTheExperiment() throws Exception {
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

    /** The process group of a process, read from {@code /proc/<pid>/stat} (the fifth field, after the name in brackets). */
    private static long processGroup(long pid) throws IOException {
        String line = Files.readString(Path.of("/proc", Long.toString(pid), "stat"));
        return Long.parseLong(line.substring(line.lastIndexOf(')') + 2).split(" ")[2]);
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
