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
import java.util.logging.Level;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.lang.Ast;
import io.powerj.core.lang.ParseContext;
import io.powerj.core.lang.Parser;
import io.powerj.core.lang.RedirectedStream;
import io.powerj.core.support.LogCapture;

/**
 * A mistake of the user is an error with a message, never an "internal error (details in the log)" (audit v2, R-03,
 * R-04, R-15; #167); and the operators on decimals are those of Java (FR-33).
 */
class UserMistakesTest {

    @TempDir
    Path tmp;

    private final Supervisor supervisor = new Supervisor();
    private final StringWriter out = new StringWriter();
    private final List<String> errors = new ArrayList<>();
    private Session session;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tmp.resolve("home"));
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("HOME", tmp.resolve("home").toString());
        session = new Session(tmp.resolve("home"), tmp, environment);
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(out, true), errors::add, false), Map.of(),
                CmdletRegistry.of(List.of()));
    }

    /** Runs the line as the shell does and returns everything the user would be told: the failure and the errors shown. */
    private String told(String line) {
        errors.clear();
        out.getBuffer().setLength(0);
        String failure = "";
        try (var log = new LogCapture(Supervisor.class)) {
            Outcome outcome = supervisor.run(line, () -> {
                interpreter.execute(line);
                return List.of();
            });
            if (outcome instanceof Outcome.Failure f) {
                failure = f.error().message();
            }
            assertThat(log.records()).as("nothing is logged as SEVERE for " + line)
                    .noneMatch(r -> r.getLevel().intValue() >= Level.SEVERE.intValue());
        }
        return failure + String.join("\n", errors) + out;
    }

    private void assertAnError(String line, String... fragments) {
        assertThat(told(line)).as(line).doesNotContain("internal error").contains(fragments);
    }

    private String value(String line) {
        return told(line).strip();
    }

    @Test
    void aLiteralThatOverflowsIsASyntaxError() {
        assertAnError("200000d", "too large");
        assertAnError("99999999tb", "too large");
    }

    @Test
    void anIndexThatDoesNotFitAnIntIsASyntaxError() {
        assertAnError("$a = 1; \"$a[99999999999]\"", "too large");
    }

    @Test
    void aLongIndexIsNotTruncatedToAnInt() {
        assertAnError("$l = java.util.List.of(1, 2); $i = 4294967296L; $l[$i]", "out of bounds", "4294967296");
    }

    @Test
    void aMapThatRefusesTheKeyIsAnErrorNotAnInternalOne() {
        assertAnError("$m = java.util.Map.of(); $m[null]", "key");
        assertAnError("$m = new java.util.TreeMap(); $m.put(\"a\", 1); $m[1]", "key");
    }

    @Test
    void notANumberAndInfinityNeverReachBigDecimal() {
        assertAnError("$n = 0.0 / 0.0; new java.math.BigDecimal(\"1\") + $n", "NaN");
        assertAnError("$n = 1.0 / 0.0; new java.math.BigDecimal(\"1\") < $n", "Infinity");
    }

    @Test
    void decimalsAreComparedAsJavaDoes() {
        assertThat(value("0.0 == (0.0 * -1)")).isEqualTo("true");
        assertThat(value("$n = 0.0 / 0.0; $n == $n")).isEqualTo("false");
        assertThat(value("$n = 0.0 / 0.0; $n != $n")).isEqualTo("true");
        assertThat(value("$n = 0.0 / 0.0; ($n > 1)")).isEqualTo("false");
        assertThat(value("$n = 0.0 / 0.0; ($n <= 1)")).isEqualTo("false");
        assertThat(value("(1.5 < 2)")).isEqualTo("true");
    }

    @Test
    void aUnicodeEscapeIsFourHexDigitsAndNothingElse() {
        assertThat(value("\"\\u0041\"")).isEqualTo("A");
        assertAnError("\"\\u+041\"", "escape");
        assertAnError("'\\u-041'", "escape");
    }

    @Test
    void aCharacterOutsideTheBasicPlaneHasItsOwnMessage() {
        assertAnError("'\uD83D\uDE00'", "string");
        assertThat(told("'\uD83D\uDE00'")).doesNotContain("between single quotes: 'a'");
    }

    @Test
    void aRedirectionToAnInvalidPathIsAnError() throws Exception {
        var evaluator = new Evaluator(session, _ -> {
            throw new UnsupportedOperationException();
        }, () -> new ParseContext(_ -> false, _ -> false));
        Ast.Pipeline pipeline = Parser.parse("items > \"a\\0b\"", _ -> false).steps().getFirst().statement().pipeline();

        assertThatThrownBy(() -> Redirections.target(pipeline, RedirectedStream.OUT, evaluator, session))
                .isInstanceOf(io.powerj.core.error.PjException.class).hasMessageContaining("a");
    }
}
