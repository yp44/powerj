package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.assist.Completions;
import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.runtime.Interpreter;
import io.powerj.core.runtime.Session;
import io.powerj.core.runtime.ShellIo;

/**
 * Tab completion with the real cmdlets, found by {@code ServiceLoader} as in the shell (#118): the commands that take
 * paths offer them, {@code rm}, {@code cp}, {@code mv}, {@code mkdir} and {@code touch} included, and those whose
 * positional argument is a key, a column or a condition do not.
 */
class FileCommandsCompletionTest {

    @TempDir
    Path tmp;

    private Completions completions;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tmp.resolve("files"));
        Files.writeString(tmp.resolve("fichier.txt"), "x");
        var session = new Session(tmp, tmp, new HashMap<>(System.getenv()));
        var interpreter = new Interpreter(session, new ShellIo(new PrintWriter(new StringWriter()), _ -> { }, false),
                Map.of(), CmdletRegistry.discover());
        completions = new Completions(interpreter);
    }

    private List<String> offered(String line) {
        var result = completions.complete(line, line.length());
        return result.candidates().stream().map(Completions.Candidate::value)
                .filter(v -> v.startsWith(result.word())).toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ls", "cat", "grep x", "rm", "cp", "mv", "mkdir", "touch"})
    void aCommandThatTakesPathsOffersThem(String command) {
        assertThat(offered(command + " f")).as(command).containsExactlyInAnyOrder("fichier.txt", "files" + File.separator);
    }

    @ParameterizedTest
    @ValueSource(strings = {"rm -r f", "cp -r f", "rm -f f"})
    void afterAnOptionThePathsAreStillOffered(String line) {
        assertThat(offered(line)).as(line).containsExactlyInAnyOrder("fichier.txt", "files" + File.separator);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sort", "select", "group", "kill", "where", "map", "measure"})
    void aCommandWhoseArgumentIsNotAPathOffersNoFile(String command) {
        assertThat(offered(command + " f")).as(command).doesNotContain("fichier.txt", "files" + File.separator);
    }

    @ParameterizedTest
    @ValueSource(strings = {"rm", "cp", "mv", "mkdir", "touch"})
    void theSecondPathIsCompletedToo(String command) {
        assertThat(offered(command + " fichier.txt f")).as(command)
                .containsExactlyInAnyOrder("fichier.txt", "files" + File.separator);
    }
}
