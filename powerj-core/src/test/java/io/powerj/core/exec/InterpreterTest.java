package io.powerj.core.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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
        session = new Session(tmp.resolve("home"), tmp, System.getenv());
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
        assertThat(errors).containsExactly("commande inconnue : commandeinexistante");
    }

    @Test
    void unknownCommand() throws Exception {
        run("commandeinexistante a b");
        assertThat(errors).containsExactly("commande inconnue : commandeinexistante");
        assertThat(session.lastSucceeded()).isFalse();
        run("^cd");
        assertThat(errors).containsExactly("commande native introuvable : cd");
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
        assertThat(errors).containsExactly("cd : dossier introuvable : /nulle/part");
    }

    @Test
    void nativeCommandsRunInTheSessionDirectory() throws Exception {
        Files.createDirectories(tmp.resolve("ici"));
        run("cd ici");
        assertThat(run("pwd")).contains("ici");
        assertThat(run("sh -c pwd")).isEqualTo(tmp.resolve("ici").toRealPath() + System.lineSeparator());
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
        assertThat(Files.readString(tmp.resolve("unknown.txt")).strip()).isEqualTo("commande inconnue : commandeinexistante");
    }

    @Test
    void whichDescribesCommands() throws Exception {
        assertThat(run("which cd sh")).contains("cd → commande interne", "sh → natif /");
        run("which commandeinexistante");
        assertThat(errors).containsExactly("which : introuvable : commandeinexistante");
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
        assertThat(errors).containsExactly("variable inconnue : $inconnue");
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
                throw new AssertionError("sleep n'a pas démarré");
            }
            Thread.sleep(20);
        }
        supervisor.cancel();
        worker.join(10_000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(ProcessHandle.current().children().anyMatch(p -> p.info().command().orElse("").endsWith("sleep")))
                .isFalse();
    }
}
