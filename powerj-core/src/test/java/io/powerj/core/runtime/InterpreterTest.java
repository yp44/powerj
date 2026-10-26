package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.error.PjException;
import io.powerj.core.nativeexec.NativeRun;
import io.powerj.core.nativeexec.Platform;

/** Runs real native commands ({@code sh}, {@code printf}…): tests limited to Linux/macOS. */
class InterpreterTest {

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = new ArrayList<>();
    private Session session;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() throws Exception {
        assumeFalse(Platform.isWindows(), "commandes Unix");
        Files.createDirectories(tmp.resolve("home"));
        Map<String, String> environment = new java.util.HashMap<>(System.getenv());
        environment.put("HOME", tmp.resolve("home").toString()); // ~ follows HOME (FR-62)
        session = new Session(tmp.resolve("home"), tmp, environment);
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(out, true), errors::add, false), Map.of(),
                CmdletRegistry.of(List.of()));
    }

    private String run(String line) throws Exception {
        out.getBuffer().setLength(0);
        errors.clear();
        interpreter.execute(line);
        return out.toString();
    }

    @Test
    void nativeOutputIsDisplayedLineByLine() throws Exception {
        assertThat(run("printf \"a\\nb\\n\"")).isEqualTo("a" + System.lineSeparator() + "b" + System.lineSeparator());
        assertThat(session.lastNative()).get().extracting(NativeRun::exitCode).isEqualTo(0);
    }

    @Test
    void stderrGoesToTheErrorStreamNeverMixedWithOutput() throws Exception {
        assertThat(run("sh -c \"echo dehors; echo erreur >&2\"")).isEqualTo("dehors" + System.lineSeparator());
        assertThat(errors).containsExactly("erreur");
    }

    @Test
    void assignmentCapturesLines() throws Exception {
        run("$l = printf \"un\\ndeux\\n\"");
        assertThat(session.variable("l")).isEqualTo(List.of("un", "deux"));
        assertThat(run("$l[0]")).isEqualTo("un" + System.lineSeparator());
        assertThat(run("$l[-1]")).isEqualTo("deux" + System.lineSeparator());

        run("$one = printf \"seule\"");
        assertThat(session.variable("one")).isEqualTo("seule");
    }

    @Test
    void exitCodeAndLastRun() throws Exception {
        run("sh -c \"exit 3\"");
        assertThat(run("$exit")).isEqualTo("3" + System.lineSeparator());
        assertThat(run("$?")).isEqualTo("true" + System.lineSeparator()); // $exit itself succeeded
        run("sh -c \"exit 3\"");
        assertThat(session.lastSucceeded()).isFalse();
        assertThat(run("$last.exitCode")).isEqualTo("3" + System.lineSeparator());
        assertThat(run("$last.duration")).endsWith(" s" + System.lineSeparator());
        assertThat(run("$last")).contains("command", "exitCode", "/sh", "[-c, exit 3]");
    }

    @Test
    void chainingFollowsSuccess() throws Exception {
        assertThat(run("sh -c \"exit 0\" && \"oui\" || \"non\"")).isEqualTo("oui" + System.lineSeparator());
        assertThat(run("sh -c \"exit 1\" && \"oui\" || \"non\"")).isEqualTo("non" + System.lineSeparator());
        assertThat(run("sh -c \"exit 1\" ; \"toujours\"")).isEqualTo("toujours" + System.lineSeparator());
        // true / false are literals (as in Java); their value decides the chaining
        assertThat(run("false || \"non\"")).isEqualTo("false" + System.lineSeparator() + "non" + System.lineSeparator());
        assertThat(run("commandeinexistante || \"secours\"")).isEqualTo("secours" + System.lineSeparator());
        assertThat(errors).containsExactly("unknown command: commandeinexistante");
    }

    @Test
    void unknownCommand() throws Exception {
        run("commandeinexistante a b");
        assertThat(errors).containsExactly("unknown command: commandeinexistante");
        assertThat(session.lastSucceeded()).isFalse();
        // not "cd": macOS has a /usr/bin/cd (a script that runs the builtin of sh, for POSIX completeness)
        run("^no-such-program-for-powerj");
        assertThat(errors).containsExactly("native command not found: no-such-program-for-powerj");
    }

    @Test
    void cdPwdAndPrevious() throws Exception {
        Files.createDirectories(tmp.resolve("a/b"));
        run("cd a/b");
        assertThat(session.currentDirectory()).isEqualTo(tmp.resolve("a/b"));
        run("cd ..");
        assertThat(run("pwd")).isEqualTo(tmp.resolve("a") + System.lineSeparator());
        run("cd -");
        assertThat(session.currentDirectory()).isEqualTo(tmp.resolve("a/b"));
        run("cd ~");
        assertThat(session.currentDirectory()).isEqualTo(tmp.resolve("home"));
        run("cd");
        assertThat(session.currentDirectory()).isEqualTo(tmp.resolve("home"));
        run("cd /nulle/part");
        assertThat(errors).containsExactly("cd: directory not found: /nulle/part");
    }

    @Test
    void nativeCommandsRunInTheSessionDirectory() throws Exception {
        Files.createDirectories(tmp.resolve("ici"));
        run("cd ici");
        assertThat(run("pwd")).contains("ici");
        assertThat(run("sh -c pwd")).isEqualTo(tmp.resolve("ici").toRealPath() + System.lineSeparator());
    }

    @Test
    void aNativeCommandSeesTheSessionEnvironmentAndNothingOfTheJvm() throws Exception {
        String inherited = System.getenv().keySet().stream()
                .filter(name -> name.matches("[A-Z][A-Z_]*") && !name.equals("PATH") && !name.equals("HOME"))
                .findFirst().orElse(null);
        assumeTrue(inherited != null, "the JVM has an environment variable to leave out");
        session.environment().remove(inherited);
        session.environment().put("POWERJ_PROBE", "from-the-session");

        assertThat(run("printenv POWERJ_PROBE")).isEqualTo("from-the-session" + System.lineSeparator());
        assertThat(run("printenv HOME")).isEqualTo(tmp.resolve("home") + System.lineSeparator());
        assertThat(run("printenv " + inherited)).isEmpty();
    }

    @Test
    void redirections() throws Exception {
        run("printf \"a\\n\" > out.txt");
        run("printf \"b\\n\" >> out.txt");
        assertThat(Files.readString(tmp.resolve("out.txt"))).isEqualTo("a\nb\n");

        run("sh -c \"echo oups >&2\" 2> err.txt");
        assertThat(Files.readString(tmp.resolve("err.txt"))).isEqualTo("oups\n");
        assertThat(errors).isEmpty();

        run("pwd > pwd.txt");
        assertThat(Files.readString(tmp.resolve("pwd.txt")).strip()).isEqualTo(tmp.toString());

        run("commandeinexistante 2> unknown.txt");
        assertThat(Files.readString(tmp.resolve("unknown.txt")).strip()).isEqualTo("unknown command: commandeinexistante");
    }

    @Test
    void whichDescribesCommands() throws Exception {
        assertThat(run("which cd sh")).contains("cd → built-in command", "sh → native /");
        run("which commandeinexistante");
        assertThat(errors).containsExactly("which: not found: commandeinexistante");
    }

    @Test
    void exitStopsTheLine() throws Exception {
        assertThat(run("exit 5; \"jamais\"")).isEmpty();
        assertThat(session.exitRequest()).hasValue(5);
    }

    @Test
    void variablesAndInterpolation() throws Exception {
        run("$nom = \"Yves\"");
        assertThat(run("\"Bonjour $nom !\"")).isEqualTo("Bonjour Yves !" + System.lineSeparator());
        run("$inconnue");
        assertThat(errors).containsExactly("unknown variable: $inconnue");
    }

    @Test
    void argumentsArePassedVerbatim() throws Exception {
        run("$x = printf \"%s|\" a\\b \"c d\" $home");
        assertThat(session.variable("x")).isEqualTo("a\\b|c d|" + tmp.resolve("home") + "|");
    }

    @Test
    void syntaxErrorRunsNothing() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> run("\"ok\" && "))
                .isInstanceOf(PjException.class);
    }

    @Test
    void ctrlCKillsTheNativeCommand() throws Exception {
        var supervisor = new Supervisor();
        var worker = Thread.ofVirtual().start(() -> supervisor.run("sleep", () -> {
            interpreter.execute("sleep 30");
            return List.of();
        }));
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (ProcessHandle.current().children().noneMatch(p -> p.info().command().orElse("").endsWith("sleep"))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("sleep did not start");
            }
            Thread.sleep(20);
        }
        supervisor.cancel();
        worker.join(10_000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(ProcessHandle.current().children().anyMatch(p -> p.info().command().orElse("").endsWith("sleep")))
                .isFalse();
    }

    @Test
    void backgroundCommandGivesControlBackAtOnce() throws Exception {
        long start = System.nanoTime();
        run("sleep 30 &");
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(5));
        assertThat(errors).isEmpty();
        NativeRun last = session.lastNative().orElseThrow();
        assertThat(last.exitCode()).isNull();
        assertThat(session.lastSucceeded()).isTrue();
        ProcessHandle sleep = ProcessHandle.of(last.pid()).orElseThrow();
        try {
            assertThat(sleep.isAlive()).isTrue();
            assertThat(session.jobs().list()).singleElement().satisfies(job -> {
                assertThat(job.id()).isEqualTo(1);
                assertThat(job.pid()).isEqualTo(last.pid());
                assertThat(job.command()).isEqualTo("sleep 30");
                assertThat(job.running()).isTrue();
            });
            // Ctrl+C in the terminal reaches the whole process group: the background command ignores it.
            new ProcessBuilder("kill", "-INT", String.valueOf(last.pid())).start().waitFor();
            // The one wait of this suite that is a duration on purpose: "the process did NOT die" can only be
            // observed by letting some time pass (a death would be immediate).
            Thread.sleep(300);
            assertThat(sleep.isAlive()).isTrue();
        } finally {
            sleep.destroyForcibly();
        }
        sleep.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
        awaitEnd();
        assertThat(session.jobs().drainFinished()).singleElement().asString().startsWith("[1] done sleep 30 (exit ");
        assertThat(session.jobs().list()).isEmpty();
    }

    @Test
    void backgroundRedirectionsPipelinesAndNextStatement() throws Exception {
        assertThat(run("sh -c \"echo dehors; echo erreur >&2\" > out.txt 2> err.txt & printf \"suite\""))
                .isEqualTo("suite" + System.lineSeparator());
        run("printf \"b\\na\\n\" | sort > tri.txt &");
        awaitEnd();
        assertThat(Files.readString(tmp.resolve("out.txt"))).isEqualTo("dehors\n");
        assertThat(Files.readString(tmp.resolve("err.txt"))).isEqualTo("erreur\n");
        assertThat(Files.readString(tmp.resolve("tri.txt"))).isEqualTo("a\nb\n");
        // jobs reports the ended jobs once, then forgets them.
        assertThat(session.jobs().list()).extracting(Job::id, Job::command, Job::running, Job::exitCode)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "sh -c \"echo dehors; echo erreur >&2\"", false, 0),
                        org.assertj.core.groups.Tuple.tuple(2, "printf \"b\\na\\n\" | sort", false, 0));
        assertThat(session.jobs().list()).isEmpty();
        assertThat(session.jobs().drainFinished()).isEmpty();
    }

    @Test
    void backgroundOnlyForNativeCommands() throws Exception {
        String message = "& only applies to native commands (programs), not to cmdlets, built-in commands or expressions";
        run("pwd &");
        assertThat(errors).containsExactly(message);
        run("\"x\" &");
        assertThat(errors).containsExactly(message);
        assertThat(session.jobs().list()).isEmpty();
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

    @Test
    void tildeIsTheHomeVariableInUnquotedArguments() throws Exception {
        Path home = tmp.resolve("home");
        String nl = System.lineSeparator();
        // Native command arguments: ~ and ~/x are expanded, quoted or embedded ~ are not.
        assertThat(run("printf \"%s\\n\" ~ ~/notes.txt \"~\" a~b ~user")).isEqualTo(
                home + nl + home.resolve("notes.txt") + nl + "~" + nl + "a~b" + nl + "~user" + nl);
        // Redirections.
        run("printf \"x\\n\" > ~/out.txt");
        assertThat(Files.readString(home.resolve("out.txt"))).isEqualTo("x\n");
        // Built-in commands.
        Files.createDirectories(home.resolve("projets"));
        run("cd ~/projets");
        assertThat(session.currentDirectory()).isEqualTo(home.resolve("projets"));
        // ~ follows the HOME variable of the session, changed at runtime (env --set HOME=…).
        Path other = Files.createDirectories(tmp.resolve("other"));
        session.environment().put("HOME", other.toString());
        run("cd ~");
        assertThat(session.currentDirectory()).isEqualTo(other);
        assertThat(errors).isEmpty();
    }

    @Test
    void homeFallsBackToUserProfileThenToTheStartupDirectory() {
        Path start = tmp.resolve("start");
        var withProfile = new Session(start, tmp, Map.of("USERPROFILE", tmp.resolve("profile").toString()));
        assertThat(withProfile.home()).isEqualTo(tmp.resolve("profile"));
        assertThat(new Session(start, tmp, Map.of("HOME", " ")).home()).isEqualTo(start);
        assertThat(new Session(start, tmp, Map.of()).expandTilde("~/a")).isEqualTo(start.resolve("a").toString());
        assertThat(new Session(start, tmp, Map.of()).expandTilde("~x")).isEqualTo("~x");
    }
}
