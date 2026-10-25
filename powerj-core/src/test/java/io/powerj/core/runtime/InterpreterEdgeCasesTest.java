package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.powerj.core.support.FakeCmdlets;

/** Less common paths of the interpreter: redirections, which, mod-load, preview of every kind of value. */
class InterpreterEdgeCasesTest {

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = new ArrayList<>();
    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        var session = new Session(tmp, tmp, Map.of("PATH", ""));
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(out, true), errors::add, false), Map.of(),
                CmdletRegistry.of(FakeCmdlets.all()));
    }

    private List<String> errors(String line) throws Exception {
        errors.clear();
        interpreter.execute(line);
        return errors;
    }

    @Test
    void commandKinds() {
        assertThat(interpreter.commandKind("cd")).isEqualTo(Interpreter.CommandKind.BUILTIN);
        assertThat(interpreter.commandKind("items")).isEqualTo(Interpreter.CommandKind.CMDLET);
        assertThat(interpreter.commandKind("^items")).isEqualTo(Interpreter.CommandKind.UNKNOWN);
        assertThat(interpreter.commandKind("^cd")).isEqualTo(Interpreter.CommandKind.UNKNOWN);
        assertThat(interpreter.commandKind("^")).isEqualTo(Interpreter.CommandKind.UNKNOWN);
    }

    @Test
    void outputRedirectionsThatAreNotAllowed() throws Exception {
        assertThat(errors("$x = items > out.txt")).containsExactly("an assignment cannot redirect its output with >");
        assertThat(errors("$x = (items > out.txt)")).containsExactly("\">\" is not allowed in a subexpression ( )");
        assertThat(tmp.resolve("out.txt")).doesNotExist();
    }

    @Test
    void appendingRedirections() throws Exception {
        interpreter.execute("items -n 1 >> out.txt");
        interpreter.execute("items -n 1 >> out.txt");
        assertThat(Files.readAllLines(tmp.resolve("out.txt"))).filteredOn(l -> l.contains("item1")).hasSize(2);
        interpreter.execute("nope 2>> err.txt");
        interpreter.execute("nope 2>> err.txt");
        assertThat(Files.readAllLines(tmp.resolve("err.txt"))).containsExactly("unknown command: nope",
                "unknown command: nope");
    }

    @Test
    void writingIntoAFolderIsAnError() throws Exception {
        Files.createDirectories(tmp.resolve("dir"));
        assertThat(errors("items > dir")).singleElement().asString().startsWith("cannot write to " + tmp.resolve("dir"));
    }

    @Test
    void whichErrors() throws Exception {
        assertThat(errors("which")).containsExactly("which: command name expected");
        assertThat(errors("which ^nope")).containsExactly("which: program not found: nope");
        assertThat(errors("which nope")).containsExactly("which: not found: nope");
    }

    @Test
    void modLoadErrors() throws Exception {
        assertThat(errors("mod-load")).containsExactly("mod-load: path of a module (.jar or directory) expected");
        assertThat(errors("mod-load nope.jar")).last().asString().isEqualTo("mod-load: nope.jar not loaded");
    }

    @Test
    void previewOfEveryKindOfValue() {
        assertThat(interpreter.preview(";", 10, 80, false)).isInstanceOf(PreviewResult.Empty.class);
        assertThat(interpreter.preview("items &", 10, 80, false))
                .isEqualTo(new PreviewResult.Off("background command (&)"));
        assertThat(values("List.of(1, 2).stream()")).containsExactly(1, 2);
        assertThat(values("List.of(3).iterator()")).containsExactly(3);
        assertThat(values("\"a,b\".split(\",\")")).containsExactly("a", "b");
        assertThat(values("null")).isEmpty();
    }

    private List<Object> values(String line) {
        return ((PreviewResult.Values) interpreter.preview(line, 10, 80, false)).values();
    }
}
