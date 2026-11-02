package io.powerj.core.assist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.runtime.Interpreter;
import io.powerj.core.runtime.Session;
import io.powerj.core.runtime.ShellIo;

/**
 * Tab completion of a positional argument typed {@code Object} (#118): a cmdlet that takes a path or a block, like
 * {@code rm}, says so with {@code path = true}; one that takes keys or conditions does not, and gets no file.
 */
class PathPositionalCompletionTest {

    @CmdletInfo(name = "removal", summary = "Paths or a block")
    static final class Removal implements Cmdlet<Removal.Params, Void, String> {
        record Params(@Option(position = 0, path = true, description = "Paths, or a block") List<Object> paths,
                      @Option(shortName = 'f', description = "Force") boolean force) { }
    }

    @CmdletInfo(name = "single", summary = "One path or a block")
    static final class Single implements Cmdlet<Single.Params, Void, String> {
        record Params(@Option(position = 0, path = true, description = "A path, or a block") Object target) { }
    }

    @CmdletInfo(name = "keys", summary = "Keys, not paths")
    static final class Keys implements Cmdlet<Keys.Params, Void, String> {
        record Params(@Option(position = 0, description = "Keys or blocks") List<Object> keys) { }
    }

    @TempDir
    Path tmp;

    private Completions completions;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tmp.resolve("files"));
        Files.writeString(tmp.resolve("fichier.txt"), "x");
        var session = new Session(tmp, tmp, Map.of("PATH", ""));
        var interpreter = new Interpreter(session, new ShellIo(new PrintWriter(new StringWriter()), _ -> { }, false),
                Map.of(), CmdletRegistry.of(List.of(new Removal(), new Single(), new Keys())));
        completions = new Completions(interpreter);
    }

    private List<String> offered(String line) {
        var result = completions.complete(line, line.length());
        return result.candidates().stream().map(Completions.Candidate::value)
                .filter(v -> v.startsWith(result.word())).toList();
    }

    @Test
    void aPositionalObjectListMarkedAsPathsOffersFiles() {
        assertThat(offered("removal f")).containsExactlyInAnyOrder("fichier.txt", "files" + File.separator);
        assertThat(offered("removal fi")).containsExactlyInAnyOrder("fichier.txt", "files" + File.separator);
        assertThat(offered("removal fichier.txt f")).containsExactlyInAnyOrder("fichier.txt", "files" + File.separator);
    }

    @Test
    void aSinglePositionalObjectMarkedAsPathOffersFiles() {
        assertThat(offered("single f")).containsExactlyInAnyOrder("fichier.txt", "files" + File.separator);
    }

    @Test
    void anOptionStillOffersItsOwnWordsAfterADash() {
        assertThat(offered("removal --f")).contains("--force");
    }

    @Test
    void aPositionalObjectNotMarkedOffersNoFile() {
        assertThat(offered("keys f")).isEmpty();
    }
}
