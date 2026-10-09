package io.powerj.core.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Alignment with Java (step 5b, FR-33b): lambdas, method references, strict booleans, text blocks. */
class JavaAlignmentTest {

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

    private String run(String line) throws Exception {
        out.getBuffer().setLength(0);
        errors.clear();
        interpreter.execute(line);
        return out.toString().replace(System.lineSeparator(), "\n");
    }

    private Object value(String expression) throws Exception {
        run("$r = " + expression);
        assertThat(errors).isEmpty();
        return session.variable("r");
    }

    @Test
    void namedLambdasInBlocks() throws Exception {
        assertThat(run("items | filter { i -> i.size > 15 && i.name.startsWith(\"item\") } | count")).isEqualTo("2\n");
        run("$min = 20");
        assertThat(run("items | filter { i -> i.size >= $min } | count")).isEqualTo("2\n");
        // $_ remains available in a block without a declared parameter.
        assertThat(run("items | filter { $_.size == 10 } | count")).isEqualTo("1\n");
    }

    @Test
    void nestedLambdaKeepsTheOuterParameter() throws Exception {
        assertThat(run("items | filter { i -> List.of(\"item1\", \"item3\").stream().anyMatch(n -> n == i.name) } | count"))
                .isEqualTo("2\n");
    }

    @Test
    void lambdasWithoutBracesInJavaCalls() throws Exception {
        run("$l = List.of(\"apple\", \"banana\", \"kiwi\")");
        assertThat(value("$l.stream().filter(s -> s.length() > 4).map(s -> s.toUpperCase()).toList()"))
                .isEqualTo(List.of("APPLE", "BANANA"));
        assertThat(value("$l.stream().sorted((a, b) -> b.compareTo(a)).toList()")).isEqualTo(List.of("kiwi", "banana", "apple"));
        assertThat(value("Optional.empty().orElseGet(() -> 42)")).isEqualTo(42);
    }

    @Test
    void methodReferences() throws Exception {
        run("$l = List.of(\"apple\", \"kiwi\")");
        assertThat(value("$l.stream().map(String::toUpperCase).toList()")).isEqualTo(List.of("APPLE", "KIWI"));
        assertThat(value("$l.stream().map(String::length).toList()")).isEqualTo(List.of(5, 4));
        assertThat(value("$l.stream().map(Path::of).toList()")).isEqualTo(List.of(Path.of("apple"), Path.of("kiwi")));
        assertThat(value("Stream.of(\"a\", \"b\").map(StringBuilder::new).map(StringBuilder::reverse).map(String::valueOf).toList()"))
                .isEqualTo(List.of("a", "b"));
        assertThat(value("$l.stream().filter(\"kiwi\"::equals).count()")).isEqualTo(1L);
        run("$s = \"kiwi\"");
        assertThat(value("$l.stream().anyMatch($s::equals)")).isEqualTo(true);
        assertThat(value("java.util.List.of(3, 1, 2).stream().map(Integer::toBinaryString).toList()"))
                .isEqualTo(List.of("11", "1", "10"));
    }

    @Test
    void methodReferenceOnACmdletOutputType() throws Exception {
        assertThat(run("items | eval-each Item::name")).isEqualTo("item1\nitem2\nitem3\n");
        run("items -n 1 | eval-each String::length");
        assertThat(errors).singleElement().asString().contains("String::length", "Item");
    }

    @Test
    void conditionsMustBeBooleans() throws Exception {
        assertThat(run("items | filter { i -> i.name } | count")).isEqualTo("0\n");
        assertThat(errors).hasSize(3).allSatisfy(e -> assertThat(e).contains("the block must return a boolean", "String"));
        run("$x = (1 && true)");
        assertThat(errors).singleElement().asString().contains("\"&&\" expects a boolean");
    }

    @Test
    void parameterCountsAreChecked() throws Exception {
        run("$m = new ArrayList(List.of(\"b\", \"a\"))");
        run("$m.sort({ $a.compareTo($b) })");
        assertThat(errors).singleElement().asString().contains("this block receives 2 arguments", "(a, b) ->");
        run("$m.sort({ x -> x })");
        assertThat(errors).singleElement().asString().contains("declares 1 parameter, 2 received");
    }

    @Test
    void lambdaSyntaxErrors() {
        assertThatThrownBy(() -> interpreter.execute("items | filter i -> i.size"))
                .hasMessageContaining("a lambda is written in braces");
        assertThatThrownBy(() -> interpreter.execute("items | filter { i -> }")).hasMessageContaining("lambda body expected");
        assertThatThrownBy(() -> interpreter.execute("items | filter { (a, a) -> 1 }")).hasMessageContaining("duplicate lambda parameter");
    }

    @Test
    void contextCompileAcceptsLambdas() throws Exception {
        assertThat(run("items | filter \"i -> i.size == 20\" | count")).isEqualTo("1\n");
    }

    @Test
    void textBlocks() throws Exception {
        assertThat(value("\"\"\"\n    Bonjour \"monde\"\n      n=$(1 + 1)\n    \"\"\"")).isEqualTo("Bonjour \"monde\"\n  n=2\n");
        assertThat(value("\"\"\"\n    a\n    b\"\"\".lines().count()")).isEqualTo(2L);
        assertThatThrownBy(() -> interpreter.execute("\"\"\"abc\"\"\"")).hasMessageContaining("line break");
    }
}
