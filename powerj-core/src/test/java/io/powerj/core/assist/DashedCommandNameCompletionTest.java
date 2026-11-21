package io.powerj.core.assist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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

/** Tab completion of a command name that contains a dash, once the dash is typed (#243): mod-loa, to-j, ^apt-g. */
class DashedCommandNameCompletionTest {

    @CmdletInfo(name = "to-json", summary = "To JSON", preview = true)
    static final class ToJson implements Cmdlet<ToJson.Params, Object, String> {
        record Params(@io.powerj.api.Option(description = "Indented") boolean pretty) { }
    }

    @CmdletInfo(name = "to-csv", summary = "To CSV")
    static final class ToCsv implements Cmdlet<ToCsv.Params, Object, String> {
        record Params() { }
    }

    @TempDir
    Path tmp;

    private Completions completions;

    @BeforeEach
    void setUp() throws Exception {
        Path bin = Files.createDirectories(tmp.resolve("bin"));
        Path tool = Files.writeString(bin.resolve("apt-get"), "#!/bin/sh\n");
        tool.toFile().setExecutable(true);
        Files.writeString(bin.resolve("apt-get.exe"), "");
        Files.writeString(tmp.resolve("loader.txt"), "x");
        Map<String, String> env = new HashMap<>();
        env.put("PATH", bin.toString());
        env.put("PATHEXT", ".EXE");
        var session = new Session(tmp, tmp, env);
        var interpreter = new Interpreter(session, new ShellIo(new PrintWriter(new StringWriter()), _ -> { }, false),
                Map.of(), CmdletRegistry.of(List.of(new ToJson(), new ToCsv())));
        completions = new Completions(interpreter);
    }

    /** Proposed values that extend the current word (the final filtering is done by JLine). */
    private List<String> offered(String line) {
        var result = completions.complete(line, line.length());
        return result.candidates().stream().map(Completions.Candidate::value)
                .filter(v -> v.startsWith(result.word())).toList();
    }

    @Test
    void aBuiltInCommandIsCompletedAfterItsDash() {
        assertThat(offered("mod-loa")).containsExactly("mod-load");
        assertThat(offered("mod-l")).containsExactlyInAnyOrder("mod-load", "mod-list");
        assertThat(offered("mod-")).containsExactlyInAnyOrder("mod-load", "mod-list");
    }

    @Test
    void theWholeNameIsReplaced() {
        var result = completions.complete("mod-loa", 7);
        assertThat(result.start()).isZero();
        assertThat(result.word()).isEqualTo("mod-loa");
    }

    @Test
    void aCmdletIsCompletedAfterItsDash() {
        assertThat(offered("to-j")).containsExactly("to-json");
        assertThat(offered("to-")).containsExactlyInAnyOrder("to-json", "to-csv");
    }

    @Test
    void aNativeProgramIsCompletedAfterItsDash() {
        assertThat(offered("apt-g")).containsExactly("apt-get");
        assertThat(offered("^apt-g")).containsExactly("apt-get");
    }

    @Test
    void everyPlaceWhereACommandStartsIsCompleted() {
        assertThat(offered("mod-list ; mod-lo")).containsExactly("mod-load");
        assertThat(offered("ls && to-j")).containsExactly("to-json");
        assertThat(offered("ls | to-j")).containsExactly("to-json");
        assertThat(offered("$x = mod-li")).containsExactly("mod-list");
    }

    @Test
    void theCursorInTheMiddleOfTheLineCompletesTheNameBeforeIt() {
        var result = completions.complete("mod-loa x.jar", 7);
        assertThat(result.start()).isZero();
        assertThat(result.candidates()).extracting(Completions.Candidate::value).contains("mod-load");
    }

    @Test
    void aDashThatIsNotInACommandNameKeepsItsMeaning() {
        assertThat(offered("to-json --pre")).containsExactly("--pretty"); // an option
        assertThat(offered("cat lo")).containsExactly("loader.txt"); // an argument
        assertThat(offered("cat x-lo")).isEmpty(); // a dash inside an argument does not make it a command
        assertThat(offered("-mod-l")).isEmpty(); // a word that starts with a dash is not a name
        assertThat(offered("$a-mod")).doesNotContain("mod-load", "mod-list"); // an operator
        assertThat(offered("(1 + $a-mo")).doesNotContain("mod-load", "mod-list"); // an expression
    }
}
