package io.powerj.core.assist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.assist.Completions.Candidate;
import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.runtime.Interpreter;
import io.powerj.core.runtime.Session;
import io.powerj.core.runtime.ShellIo;
import io.powerj.fs.Host;

/**
 * Tab completion of a path follows the rule of the host about the case of names (#86): ignored on Windows and macOS,
 * which tell {@code docs} from {@code Docs} no more than their file systems do, and kept on Linux. The same rule as the
 * protection of {@code rm}.
 */
class PathCompleterCaseTest {

    /** What follows the name of a folder in a suggestion: the separator of the system when nothing typed has one. */
    private static final String DIR = Host.windows() ? "Docs\\" : "Docs/";

    @TempDir
    Path tmp;

    private Interpreter interpreter;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectory(tmp.resolve("Docs"));
        Files.writeString(tmp.resolve("README.md"), "x");
        Files.writeString(tmp.resolve("docker.txt"), "x");
        var session = new Session(tmp, tmp, Map.of("PATH", ""));
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(new StringWriter()), _ -> { }, false),
                Map.of(), CmdletRegistry.of(List.of()));
    }

    private List<String> offered(PathCompleter completer, String word) {
        String line = "cat " + word;
        return completer.paths(line, 4, line.length()).candidates().stream().map(Candidate::value).toList();
    }

    @Test
    void whereCaseIsIgnoredDocFindsDocsAndDocker() {
        var completer = new PathCompleter(interpreter, true);

        assertThat(offered(completer, "doc")).containsExactly(DIR, "docker.txt");
        assertThat(offered(completer, "readme")).containsExactly("README.md");
        assertThat(offered(completer, "DOCS")).containsExactly(DIR);
    }

    @Test
    void whereCaseCountsOnlyTheNamesWrittenInThatCaseAreOffered() {
        var completer = new PathCompleter(interpreter, false);

        assertThat(offered(completer, "doc")).containsExactly("docker.txt");
        assertThat(offered(completer, "Doc")).containsExactly(DIR);
        assertThat(offered(completer, "readme")).isEmpty();
    }

    @Test
    void theCompleterOfTheShellFollowsTheRealHost() {
        var completer = new PathCompleter(interpreter);

        if (Host.namesIgnoreCase()) { // Windows, macOS
            assertThat(offered(completer, "doc")).containsExactly(DIR, "docker.txt");
        } else {
            assertThat(offered(completer, "doc")).containsExactly("docker.txt");
        }
    }
}
