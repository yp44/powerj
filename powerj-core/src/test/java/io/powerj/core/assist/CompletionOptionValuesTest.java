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

/** Completion of the value of an option according to its type (FR-23): enum constants, paths. */
class CompletionOptionValuesTest {

    enum Level { LOW, HIGH }

    @CmdletInfo(name = "opts", summary = "Options of every kind")
    static final class Opts implements Cmdlet<Opts.Params, Void, String> {

        record Params(@Option(shortName = 'l', description = "Level") Level level,
                      @Option(shortName = 'o', description = "Output file") Path out,
                      @Option(description = "Input files") List<Path> inputs,
                      @Option(description = "Label") String label) { }
    }

    @TempDir
    Path tmp;

    private Completions completions;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tmp.resolve("docs"));
        Files.writeString(tmp.resolve("notes.txt"), "x");
        var session = new Session(tmp, tmp, Map.of("PATH", ""));
        var interpreter = new Interpreter(session, new ShellIo(new PrintWriter(new StringWriter()), _ -> { }, false),
                Map.of(), CmdletRegistry.of(List.of(new Opts())));
        completions = new Completions(interpreter);
    }

    private List<String> values(String line) {
        var result = completions.complete(line, line.length());
        return result.candidates().stream().map(Completions.Candidate::value)
                .filter(v -> v.startsWith(result.word())).toList();
    }

    @CmdletInfo(name = "odd", summary = "A short name that a regular expression would not survive")
    static final class Odd implements Cmdlet<Odd.Params, Void, String> {

        record Params(@Option(shortName = '?', description = "Odd") boolean odd,
                      @Option(shortName = '.', description = "Dot") boolean dot,
                      @Option(shortName = 'x', description = "Plain") boolean plain) { }
    }

    @Test
    void aShortNameThatIsASpecialCharacterOfRegularExpressionsDoesNotSilenceCompletion() {
        var session = new Session(tmp, tmp, Map.of("PATH", ""));
        var interpreter = new Interpreter(session, new ShellIo(new PrintWriter(new StringWriter()), _ -> { }, false),
                Map.of(), CmdletRegistry.of(List.of(new Odd())));
        var odd = new Completions(interpreter);

        var result = odd.complete("odd -? -", 8);
        assertThat(result.candidates()).extracting(Completions.Candidate::value).contains("--dot", "--plain");
        assertThat(result.candidates()).extracting(Completions.Candidate::value).doesNotContain("--odd");
    }

    @Test
    void enumConstantsInLowerCase() {
        assertThat(values("opts --level ")).containsExactly("low", "high");
        assertThat(values("opts -l h")).containsExactly("high");
    }

    @Test
    void pathsForPathOptions() {
        assertThat(values("opts --out d")).containsExactly("docs" + File.separator);
        assertThat(values("opts -o no")).containsExactly("notes.txt");
        assertThat(values("opts --inputs no")).containsExactly("notes.txt");
    }

    @Test
    void nothingForOtherValues() {
        assertThat(values("opts --label ")).isEmpty();
        assertThat(values("opts ")).isEmpty();
    }
}
