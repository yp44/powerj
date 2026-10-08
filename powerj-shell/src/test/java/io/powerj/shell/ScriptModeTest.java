package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.exec.CmdletRegistry;
import io.powerj.core.exec.Platform;
import io.powerj.core.exec.Session;

/** Non-interactive mode: {@code powerj -c}, {@code .pj} file, standard input (FR-04d). */
class ScriptModeTest {

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = new ArrayList<>();

    private int run(Optional<Iterator<String>> input, String... lines) {
        var session = new Session(tmp, tmp, System.getenv());
        var script = new ScriptMode(session, CmdletRegistry.discover(), new PrintWriter(out, true), errors::add, 120,
                input);
        return script.run(List.of(lines));
    }

    private String output() {
        return out.toString().replace(System.lineSeparator(), "\n");
    }

    @Test
    void successGivesZero() {
        assertThat(run(Optional.empty(), "# commentaire", "", "\"bonjour\"")).isZero();
        assertThat(output()).isEqualTo("bonjour\n");
    }

    @Test
    void exitCodeOfExit() {
        assertThat(run(Optional.empty(), "exit 7", "\"jamais\"")).isEqualTo(7);
        assertThat(output()).isEmpty();
    }

    @Test
    void blockingErrorStopsTheScriptWithOne() {
        assertThat(run(Optional.empty(), "commandeinconnue", "\"jamais\"")).isEqualTo(1);
        assertThat(errors).containsExactly("commande inconnue : commandeinconnue");
        assertThat(output()).isEmpty();
    }

    @Test
    void syntaxErrorGivesOne() {
        assertThat(run(Optional.empty(), "ls |")).isEqualTo(1);
        assertThat(errors).singleElement().asString().contains("commande attendue");
    }

    @Test
    void recoveredFailureContinues() {
        assertThat(run(Optional.empty(), "commandeinconnue || \"secours\"", "\"suite\"")).isZero();
        assertThat(output()).isEqualTo("secours\nsuite\n");
    }

    @Test
    void failedNativeGivesItsExitCode() {
        assumeFalse(Platform.isWindows());
        assertThat(run(Optional.empty(), "sh -c \"exit 3\"")).isEqualTo(3);
        assertThat(run(Optional.empty(), "sh -c \"exit 3\"", "\"apres\"")).isZero();
    }

    @Test
    void standardInputFeedsWhere() {
        Iterator<String> lines = List.of("a.txt", "b.log", "c.txt").iterator();
        assertThat(run(Optional.of(lines), "where { $_.endsWith(\".txt\") }")).isZero();
        assertThat(output()).isEqualTo("a.txt\nc.txt\n");
    }
}
