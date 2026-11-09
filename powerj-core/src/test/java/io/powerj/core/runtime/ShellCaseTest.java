package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.command.CmdletRegistry;

/**
 * The shell is case-sensitive, as Java is (#130): a name that is written in another case is another name. The built-in commands,
 * the variables, the Java classes, methods and constants were already exact; this keeps them so.
 */
class ShellCaseTest {

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = new ArrayList<>();
    private Interpreter interpreter;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tmp.resolve("home"));
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("HOME", tmp.resolve("home").toString());
        var session = new Session(tmp.resolve("home"), tmp, environment);
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(out, true), errors::add, false), Map.of(),
                CmdletRegistry.of(List.of()));
    }

    private String run(String line) throws Exception {
        out.getBuffer().setLength(0);
        errors.clear();
        interpreter.execute(line);
        return out.toString().strip();
    }

    @Test
    void aVariableIsCalledByItsExactName() throws Exception {
        run("$name = 41");
        assertThat(run("$name + 1")).isEqualTo("42");
        run("$Name");
        assertThat(errors).as("$Name is not $name").isNotEmpty();
    }

    @Test
    void aBuiltInCommandIsCalledByItsExactName() throws Exception {
        run("jobs");
        assertThat(errors).isEmpty();
        run("Jobs");
        assertThat(errors).as("Jobs is not jobs: it is taken for a program").isNotEmpty();
    }

    @Test
    void aJavaMethodIsCalledByItsExactName() throws Exception {
        assertThat(run("Math.max(1, 2)")).isEqualTo("2");
        run("Math.Max(1, 2)");
        assertThat(errors).as("Math.Max is not Math.max").isNotEmpty();
    }

    @Test
    void aJavaConstantIsCalledByItsExactName() throws Exception {
        assertThat(run("java.math.RoundingMode.HALF_UP")).isEqualTo("HALF_UP");
        run("java.math.RoundingMode.half_up");
        assertThat(errors).as("a constant is spelled as in Java").isNotEmpty();
    }

    @Test
    void aClassIsCalledByItsExactName() throws Exception {
        assertThat(run("java.util.List.of(1).size()")).isEqualTo("1");
        run("java.util.list.of(1).size()");
        assertThat(errors).as("java.util.list is not java.util.List").isNotEmpty();
    }

    @Test
    void theLiteralsAreWrittenInLowerCase() throws Exception {
        assertThat(run("true")).isEqualTo("true");
        assertThat(run("null == null")).isEqualTo("true");
        run("TRUE");
        assertThat(errors).as("TRUE is not true").isNotEmpty();
    }

    @Test
    void aUnitIsWrittenInLowerCase() throws Exception {
        assertThat(run("2kb")).isEqualTo("2048");
        assertThatThrownBy(() -> run("2KB")).as("2KB is no literal, and the message gives the units")
                .hasMessageContaining("invalid number: 2KB").hasMessageContaining("units: b kb mb gb tb");
    }
}
