package io.powerj.core.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Pipeline {@code |}, blocks {@code { }} and native commands in the pipeline (step 4). */
class PipelineTest {

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private Session session;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        session = new Session(tmp, tmp, System.getenv());
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(out, true), errors::add, false), Map.of(),
                CmdletRegistry.of(FakeCmdlets.all()));
    }

    private String run(String line) throws Exception {
        out.getBuffer().setLength(0);
        errors.clear();
        interpreter.execute(line);
        return out.toString().replace(System.lineSeparator(), "\n");
    }

    private Object eval(String block) throws Exception {
        run("$r = eval { " + block + " }");
        return session.variable("r");
    }

    @Test
    void cmdletToCmdlet() throws Exception {
        assertThat(run("items | filter { $_.size > 15 }")).isEqualTo("""
                name   size
                ----   ----
                item2    20
                item3    30
                """);
        assertThat(run("items | filter { $_.name.endsWith(\"1\") || $_.size == 30 } | count")).isEqualTo("2\n");
        assertThat(run("items -n 500 | count")).isEqualTo("500\n");
        assertThat(session.lastSucceeded()).isTrue();
    }

    @Test
    void pipelinesInAssignmentsAndSubExpressions() throws Exception {
        run("$big = items | filter { $_.size >= 20 }");
        assertThat(run("$big*.name")).isEqualTo("item2\nitem3\n");
        assertThat(run("(items | filter { !$_.name.contains(\"2\") })*.name")).isEqualTo("item1\nitem3\n");
    }

    @Test
    void contextCompileForCmdlets() throws Exception {
        assertThat(run("items | filter \"\\$_.size < 20\"")).contains("item1").doesNotContain("item2");
        assertThat(run("items | filter \"\\$_.size <\"")).isEmpty();
        assertThat(errors).singleElement().asString().contains("filter", "expression incomplète");
    }

    @Test
    void evaluationErrorsAreNonBlocking() throws Exception {
        assertThat(run("items | filter { $_.nope > 1 } | count")).isEqualTo("0\n");
        assertThat(errors).hasSize(3).allSatisfy(e -> assertThat(e).contains("filter : Item n'a pas de propriété 'nope'"));
    }

    @Test
    void stagesThatDoNotReadInputAreRejected() throws Exception {
        run("items | items");
        assertThat(errors).containsExactly("« items » ne lit pas les objets du pipeline");
        run("items | cd /");
        assertThat(errors).containsExactly("« cd » ne lit pas les objets du pipeline");
        assertThat(session.lastSucceeded()).isFalse();
    }

    @Test
    void valuesAndBuiltinsAsFirstStage() throws Exception {
        run("$l = items");
        assertThat(run("$l | count")).isEqualTo("3\n");
        assertThat(run("pwd | count")).isEqualTo("1\n");
    }

    @Test
    void operators() throws Exception {
        assertThat(eval("1 + 2 * 3")).isEqualTo(7);
        assertThat(eval("(1 + 2) * 3")).isEqualTo(9);
        assertThat(eval("7 / 2")).isEqualTo(3);
        assertThat(eval("7 / 2.0")).isEqualTo(3.5);
        assertThat(eval("7 % 3")).isEqualTo(1);
        assertThat(eval("-3 + 1")).isEqualTo(-2);
        assertThat(eval("\"a\" + 1 + 2")).isEqualTo("a12");
        assertThat(eval("1 == 1L")).isEqualTo(true);
        assertThat(eval("\"ab\" == \"a\" + \"b\"")).isEqualTo(true); // value equality
        assertThat(eval("\"Ab\" == \"ab\"")).isEqualTo(false); // case-sensitive
        assertThat(eval("\"b\" > \"a\"")).isEqualTo(true);
        assertThat(eval("2 > 1 && !(1 > 2)")).isEqualTo(true);
        assertThat(eval("false || null == null")).isEqualTo(true);
        assertThat(eval("3 > 2 ? \"oui\" : \"non\"")).isEqualTo("oui");
        assertThat(eval("[1, 2, 3].size()")).isEqualTo(3);
        assertThat(eval("'a' == 'a'")).isEqualTo(true);
        assertThat(eval("'a' + 1")).isEqualTo(98);
    }

    @Test
    void shortCircuit() throws Exception {
        assertThat(eval("false && $_.nope")).isEqualTo(false);
        assertThat(eval("true || $_.nope")).isEqualTo(true);
    }

    @Test
    void unitsAndDates() throws Exception {
        assertThat(eval("10kb")).isEqualTo(10_240L);
        assertThat(eval("1.5mb")).isEqualTo(1_572_864L);
        assertThat(eval("2h")).isEqualTo(Duration.ofHours(2));
        assertThat(eval("1m + 30s")).isEqualTo(Duration.ofSeconds(90));
        assertThat(eval("now - 1d < now")).isEqualTo(true);
        assertThat(eval("10kb > 10000")).isEqualTo(true);
    }

    @Test
    void methodCalls() throws Exception {
        run("$s = \"banana\"");
        assertThat(eval("$s.contains(\"nan\")")).isEqualTo(true);
        assertThat(eval("$s.substring(1, 3)")).isEqualTo("an");
        assertThat(eval("$s.indexOf('n')")).isEqualTo(2);
        assertThat(eval("$s.toUpperCase().length()")).isEqualTo(6);
        assertThat(eval("$s.replace(\"a\", \"o\")")).isEqualTo("bonono");
        assertThat(eval("$s.matches(\"b.*a\")")).isEqualTo(true);
        assertThat(eval("$s[0]")).isEqualTo("b");
    }

    @Test
    void evaluationErrors() throws Exception {
        assertThat(run("eval { 1 / 0 }")).isEmpty();
        assertThat(errors).singleElement().asString().contains("calcul impossible", "/ by zero");
        run("eval { 1 && true }");
        assertThat(errors).singleElement().asString().contains("« && » attend un booléen, reçu Integer 1");
        run("eval { \"a\" > 1 }");
        assertThat(errors).singleElement().asString().contains("« > » impossible entre String \"a\" et Integer 1");
        run("eval { \"a\".nope() }");
        assertThat(errors).singleElement().asString().contains("String n'a pas de méthode nope()");
        run("eval { \"a\".charAt(\"x\") }");
        assertThat(errors).singleElement().asString().contains("aucune surcharge de charAt", "charAt(int)");
        run("eval { size > 1 }");
        assertThat(errors).singleElement().asString().contains("« size » inconnu", "$size");
        run("eval { \"a\".charAt(5) }");
        assertThat(errors).singleElement().asString().contains("StringIndexOutOfBoundsException");
        run("eval { $_ }");
        assertThat(errors).isEmpty();
        run("$_");
        assertThat(errors).singleElement().asString().contains("$_ n'existe que dans un bloc");
    }

    @Test
    void blockSyntaxErrors() {
        assertThatThrownBy(() -> interpreter.execute("eval { 1 + }")).hasMessageContaining("expression incomplète");
        assertThatThrownBy(() -> interpreter.execute("eval { }")).hasMessageContaining("bloc vide");
        assertThatThrownBy(() -> interpreter.execute("eval { $_.size = 1 }")).hasMessageContaining("utiliser ==");
        assertThatThrownBy(() -> interpreter.execute("eval { 3x }")).hasMessageContaining("nombre invalide : 3x");
        assertThatThrownBy(() -> interpreter.execute("eval { 'ab' }")).hasMessageContaining("apostrophes");
    }

    @Test
    void currentObjectAndStringsInBlocks() throws Exception {
        run("$n = 41");
        run("$r = eval { $_ + 1 } -w $n");
        assertThat(session.variable("r")).isEqualTo(42);
        assertThat(eval("\"n=$n, \\\"q\\\"\"")).isEqualTo("n=41, \"q\"");
        assertThat(eval("\"}\" + '}' + \"{\"")).isEqualTo("}}{"); // braces inside strings
    }

    @Test
    @Timeout(20)
    void ctrlCStopsTheWholePipeline() throws Exception {
        var failure = new AtomicReference<Throwable>();
        Thread runner = Thread.ofPlatform().start(() -> {
            try {
                interpreter.execute("infinite | filter { $_ >= 0 } | count");
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        Thread.sleep(300);
        runner.interrupt();
        runner.join();
        assertThat(failure.get()).isInstanceOfAny(InterruptedException.class, CancellationException.class);
        Thread.sleep(100);
        assertThat(Thread.getAllStackTraces().keySet()).noneMatch(t -> t.getName().startsWith("powerj-etape"));
    }

    // --- Native commands (Linux / macOS) ---

    @Test
    void nativeLinesFeedCmdlets() throws Exception {
        assumeFalse(Platform.isWindows());
        assertThat(run("printf \"un\\ndeux\\ntrois\\n\" | filter { $_.startsWith(\"d\") || $_.length() == 5 }"))
                .isEqualTo("deux\ntrois\n");
        assertThat(session.lastNative()).get().extracting(NativeRun::exitCode).isEqualTo(0);
    }

    @Test
    void nativeToNativeKeepsBytes() throws Exception {
        assumeFalse(Platform.isWindows());
        assertThat(run("printf \"b\\na\\nc\\n\" | sort -r")).isEqualTo("c\nb\na\n");
        assertThat(run("printf \"b\\na\\n\" | sort | filter { $_ == \"a\" }")).isEqualTo("a\n");
        run("$l = printf \"x\\ny\\n\" | tr a-z A-Z");
        assertThat(session.variable("l")).isEqualTo(List.of("X", "Y"));
    }

    @Test
    void objectsAreWrittenToNativeStdin() throws Exception {
        assumeFalse(Platform.isWindows());
        assertThat(run("items -n 2 | cat")).isEqualTo("""
                name   size
                ----   ----
                item1    10
                item2    20
                """);
        assertThat(run("items | grep item2")).isEqualTo("item2    20\n");
    }

    @Test
    @Timeout(20)
    void nativeThatStopsReadingStopsTheUpstreamStage() throws Exception {
        assumeFalse(Platform.isWindows());
        assertThat(run("infinite | head -n 3")).isEqualTo("0\n1\n2\n");
        assertThat(errors).isEmpty();
    }

    @Test
    void errorsToOutput() throws Exception {
        assumeFalse(Platform.isWindows());
        assertThat(run("sh -c \"echo dehors; echo erreur >&2\" 2>&1 | filter { $_ == \"erreur\" }")).isEqualTo("erreur\n");
        assertThat(errors).isEmpty();
        run("$x = sh -c \"echo erreur >&2\" 2>&1");
        assertThat(session.variable("x")).isEqualTo("erreur");
        assertThat(run("sh -c \"echo erreur >&2\" | count")).isEqualTo("0\n");
        assertThat(errors).containsExactly("erreur");
    }

    @Test
    void errorRedirectionAppliesToAllStages() throws Exception {
        assumeFalse(Platform.isWindows());
        run("sh -c \"echo e1 >&2; echo a\" | sh -c \"cat; echo e2 >&2\" | filter { $_.nope } 2> err.txt");
        assertThat(Files.readAllLines(tmp.resolve("err.txt")))
                .contains("e1", "e2").anyMatch(line -> line.contains("String n'a pas de propriété 'nope'"));
        assertThat(errors).isEmpty();
    }

    @Test
    void outputRedirectionOfAPipeline() throws Exception {
        assumeFalse(Platform.isWindows());
        run("items | filter { $_.size > 10 } > out.txt");
        assertThat(Files.readString(tmp.resolve("out.txt"))).contains("item2", "item3").doesNotContain("item1");
        run("printf \"z\\ny\\n\" | sort > sorted.txt");
        assertThat(Files.readAllLines(tmp.resolve("sorted.txt"))).containsExactly("y", "z");
    }

    @Test
    void exitStatusOfAPipeline() throws Exception {
        assumeFalse(Platform.isWindows());
        run("printf \"a\\n\" | sh -c \"cat; exit 4\"");
        assertThat(session.lastSucceeded()).isFalse();
        assertThat(session.variable("exit")).isEqualTo(4);
        run("sh -c \"exit 4\" | count");
        assertThat(session.lastSucceeded()).isTrue();
    }

    @Test
    void standardInputFeedsTheFirstStage() throws Exception {
        interpreter.useStandardInput(List.of("alpha", "beta", "gamma").iterator());
        assertThat(run("filter { $_.contains(\"a\") && !$_.startsWith(\"b\") }")).isEqualTo("alpha\ngamma\n");
        assertThat(run("filter { true } | count")).isEqualTo("0\n"); // input already consumed
    }
}
