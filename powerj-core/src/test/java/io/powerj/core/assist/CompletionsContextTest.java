package io.powerj.core.assist;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.runtime.Interpreter;
import io.powerj.core.runtime.Session;
import io.powerj.core.runtime.ShellIo;

/** Where the cursor is: a string, a block, a stage, even when a string holds an interpolation with strings of its own. */
class CompletionsContextTest {

    @CmdletInfo(name = "opts", summary = "Takes a path", preview = true)
    static final class Opts implements Cmdlet<Opts.Params, Void, String> {
        record Params(@io.powerj.api.Option(position = 0, description = "A file") Path file,
                      @io.powerj.api.Option(description = "Output") Path out) { }
    }

    @TempDir
    Path tmp;

    private Completions completions;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tmp.resolve("notes.txt"), "x");
        var session = new Session(tmp, tmp, Map.of("PATH", ""));
        var interpreter = new Interpreter(session, new ShellIo(new PrintWriter(new StringWriter()), _ -> { }, false),
                Map.of(), CmdletRegistry.of(List.of(new Opts())));
        completions = new Completions(interpreter);
    }

    private List<String> after(String line) {
        return completions.complete(line, line.length()).candidates().stream()
                .map(Completions.Candidate::value).toList();
    }

    @Test
    void aPipeInsideAnInterpolationDoesNotStartAStage() {
        // The "|" is text inside the interpolation: the options still are those of opts.
        assertThat(after("opts \"a $(f(\"|\")) c\" --ou")).contains("--out");
    }

    @Test
    void aSemicolonInsideAnInterpolationDoesNotEndTheStatement() {
        assertThat(after("opts \"a $(f(\";\")) c\" --ou")).contains("--out");
    }

    @Test
    void aStageAfterABlockWithAStringWithAnInterpolationIsACommand() {
        // The "}" is text inside the interpolation: it does not close the block, and the stage after it is a command.
        assertThat(after("opts { \"x $(f(\"}\")) y\" } | ")).contains("opts");
    }

    @Test
    void aStringWithAnInterpolationOfStringsIsOneStringBeforeTheCursorToo() {
        // The cursor is past the string: the word being typed is a path of the arguments of opts.
        assertThat(after("opts \"a $(f(\"b\")) c\" no")).contains("notes.txt");
    }

    @Test
    void insideAnUnclosedStringOfAnArgumentThePathsAreOffered() {
        assertThat(after("opts \"no")).contains("notes.txt");
    }
}
