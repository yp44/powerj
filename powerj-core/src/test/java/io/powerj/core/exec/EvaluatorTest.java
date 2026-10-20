package io.powerj.core.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Evaluation of expressions: names, calls, conversions, indexes and {@code *.} (FR-33, FR-48 to FR-58). */
class EvaluatorTest {

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = new ArrayList<>();
    private Session session;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        session = new Session(tmp, tmp, Map.of("PATH", ""));
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(out, true), errors::add, false), Map.of(),
                CmdletRegistry.of(FakeCmdlets.all()));
    }

    private Object value(String expression) throws Exception {
        errors.clear();
        interpreter.execute("$r = " + expression);
        assertThat(errors).as(expression).isEmpty();
        return session.variable("r");
    }

    private String error(String line) throws Exception {
        errors.clear();
        interpreter.execute(line);
        assertThat(errors).as(line).hasSize(1);
        return errors.getFirst();
    }

    @Test
    void classesAndStaticFields() throws Exception {
        assertThat(value("java.lang.Math").toString()).isEqualTo("java.lang.Math");
        assertThat(error("Math.NOPE")).isEqualTo("Math has no static field NOPE");
        assertThat(error("java.nope.Thing()")).isEqualTo("class not found: java.nope");
        assertThat(error("foo.bar()")).startsWith("\"foo\" unknown");
    }

    @Test
    void theConditionOfATernaryIsABoolean() throws Exception {
        assertThat(value("(2 > 1 ? \"yes\" : \"no\")")).isEqualTo("yes");
        assertThat(error("$r = (1 ? 2 : 3)")).isEqualTo("\"&&\" expects a boolean, got Integer 1");
    }

    @Test
    void systemAndRuntimeExitEndTheShell() throws Exception {
        assertThat(error("System.setOut(null)")).isEqualTo("System.setOut is refused: it would break the shell display");
        assertThat(error("System.exit(\"x\")")).isEqualTo("exit: an integer code is expected, e.g. System.exit(0)");
        interpreter.execute("Runtime.getRuntime().halt(4)");
        assertThat(session.exitRequest()).hasValue(4);
    }

    @Test
    void methodReferences() throws Exception {
        assertThat(error("java.util::foo")).isEqualTo("class not found: java.util");
        interpreter.execute("$n = null");
        assertThat(error("$n::length")).isEqualTo("method reference ::length on a null value");
        assertThat(value("Nope::name")).isInstanceOf(MethodReference.ByTypeName.class);
        assertThat(value("\"abc\"::length")).isInstanceOf(MethodReference.Bound.class);
    }

    @Test
    void explicitConversions() throws Exception {
        assertThat(error("[int] null")).isEqualTo("cannot convert null to int");
        assertThat(value("[String] null")).isNull();
        assertThat(value("[short] 5")).isEqualTo((short) 5);
        assertThat(value("[byte] 5")).isEqualTo((byte) 5);
        assertThat(value("[float] 5")).isEqualTo(5f);
        assertThat(value("[double] 5")).isEqualTo(5.0);
        assertThat(value("[char] 97")).isEqualTo('a');
        assertThat(value("[long] 'a'")).isEqualTo(97L);
        assertThat(value("[Path] \"x\"")).isEqualTo(Path.of("x"));
        assertThat(value("[CharSequence] \"x\"")).isEqualTo("x");
        assertThat(error("$r = [Integer] \"x\"")).isEqualTo("cannot convert: String \"x\" is not a Integer");
    }

    @Test
    void lambdaParametersAreAlsoVariables() throws Exception {
        assertThat(value("List.of(1, 2).stream().map(x -> $x + 1).toList()")).isEqualTo(List.of(2, 3));
        assertThat(value("Arrays.asList(null, 1).stream().map(x -> $x == null ? 0 : x).toList()"))
                .isEqualTo(List.of(0, 1));
    }

    @Test
    void indexes() throws Exception {
        assertThat(value("Map.of(\"a\", 1)[\"a\"]")).isEqualTo(1);
        assertThat(value("List.of(5, 6)[1L]")).isEqualTo(6);
        assertThat(error("$r = List.of(5)[\"x\"]")).isEqualTo("integer index expected, got String \"x\"");
    }

    @Test
    void spreadOverEveryKindOfContainer() throws Exception {
        assertThat(value("Path.of(\"a\", \"b\")*.fileName")).asString().isEqualTo("[b]");
        assertThat(value("List.of(\"ab\").iterator()*.length()")).isEqualTo(List.of(2));
        assertThat(value("\"a,bb\".split(\",\")*.length()")).isEqualTo(List.of(1, 2));
        assertThat(value("List.of(\"abc\").stream()*.length()")).isEqualTo(List.of(3));
    }

    @Test
    void elementsOfOptionalsAndArrays() {
        assertThat(Evaluator.elements(Optional.of("x"))).containsExactly("x");
        assertThat(Evaluator.elements(Optional.empty())).isEmpty();
        assertThat(Evaluator.elements(OptionalInt.of(1))).containsExactly(1);
        assertThat(Evaluator.elements(OptionalLong.of(2))).containsExactly(2L);
        assertThat(Evaluator.elements(OptionalDouble.of(3))).containsExactly(3.0);
        assertThat(Evaluator.elements(new int[] {4, 5})).containsExactly(4, 5);
        assertThat(Evaluator.elements(null)).isEmpty();
        assertThat(Evaluator.elements("x")).containsExactly("x");
    }

    @Test
    void aCaptureIsNothingOneValueOrAList() {
        assertThat(Evaluator.single(List.of())).isNull();
        assertThat(Evaluator.single(List.of("a"))).isEqualTo("a");
        assertThat(Evaluator.single(Arrays.asList("a", null))).isEqualTo(Arrays.asList("a", null));
    }
}
