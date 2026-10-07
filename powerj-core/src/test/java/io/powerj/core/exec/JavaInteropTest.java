package io.powerj.core.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Interopérabilité Java (spécification §3.13, étape 5). */
class JavaInteropTest {

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = new ArrayList<>();
    private Session session;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        session = new Session(tmp, tmp, Map.of("PATH", ""));
        interpreter = new Interpreter(session, new PrintWriterIo(out, errors).io(), Map.of(),
                CmdletRegistry.of(FakeCmdlets.all()));
    }

    /** Sorties en mémoire. */
    private record PrintWriterIo(StringWriter out, List<String> errors) {
        ShellIo io() {
            return new ShellIo(new PrintWriter(out, true), errors::add, false);
        }
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
    void staticCallsAndFields() throws Exception {
        assertThat(run("java.util.List.of(\"apple\", \"banana\", \"orange\")")).isEqualTo("apple\nbanana\norange\n");
        assertThat(run("java.lang.Math.max(3, 7)")).isEqualTo("7\n");
        assertThat(run("Math.max(3, 7)")).isEqualTo("7\n");
        assertThat(value("java.lang.Math.PI")).isEqualTo(Math.PI);
        assertThat(value("Math.PI")).isEqualTo(Math.PI);
        assertThat(value("java.time.DayOfWeek.MONDAY")).isEqualTo(DayOfWeek.MONDAY);
        assertThat(value("Integer.MAX_VALUE")).isEqualTo(Integer.MAX_VALUE);
        assertThat(value("Math.max(2.5, 1)")).isEqualTo(2.5);
        assertThat(value("Math.max(1L, 2)")).isEqualTo(2L);
    }

    @Test
    void defaultImportsAndExplicitImports() throws Exception {
        assertThat(value("LocalDate.now().plusDays(10).dayOfWeek")).isEqualTo(LocalDate.now().plusDays(10).getDayOfWeek());
        assertThat(value("UUID.randomUUID().toString().length()")).isEqualTo(36);
        run("MessageDigest.getInstance(\"SHA-256\")");
        assertThat(errors).singleElement().asString().contains("« MessageDigest » inconnu");
        run("import java.security.*");
        assertThat(value("MessageDigest.getInstance(\"SHA-256\").algorithm")).isEqualTo("SHA-256");
        assertThat(run("import")).contains("java.util.*", "java.security.*");
        run("import java.awt.List");
        assertThat(run("help List")).contains("classe java.awt.List");
    }

    @Test
    void importErrors() throws Exception {
        run("import java.nope.*");
        assertThat(errors).containsExactly("import : package introuvable dans la bibliothèque Java : java.nope");
        run("import io.powerj.core.exec.Session");
        assertThat(errors).containsExactly("import : classe introuvable dans la bibliothèque Java : io.powerj.core.exec.Session");
        run("import java.awt.*");
        run("List.of(1)");
        assertThat(errors).singleElement().asString().contains("nom ambigu : List", "java.util.List", "java.awt.List");
    }

    @Test
    void instantiationAndInstanceCalls() throws Exception {
        assertThat(value("new StringBuilder(\"ab\").reverse().toString()")).isEqualTo("ba");
        assertThat(value("new BigDecimal(\"0.1\").add(new BigDecimal(\"0.2\"))")).isEqualTo(new BigDecimal("0.3"));
        assertThat(value("new java.io.File(\"x\")")).isEqualTo(new File("x"));
        assertThat(value("\"abc\".toUpperCase().length()")).isEqualTo(3);
        run("$l = java.util.List.of(\"apple\", \"banana\")");
        assertThat(run("$l.size()")).isEqualTo("2\n");
        assertThat(value("$l.get(1).charAt(0)")).isEqualTo('b');
        assertThat(value("String.join(\", \", $l)")).isEqualTo("apple, banana");
        assertThat(value("(items).size()")).isEqualTo(3);
    }

    @Test
    void varargsAndConversions() throws Exception {
        assertThat(value("String.format(\"%s-%05d\", \"id\", 42)")).isEqualTo("id-00042");
        assertThat(value("List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11).size()")).isEqualTo(11);
        assertThat(value("Path.of(\"a\", \"b\", \"c\")")).isEqualTo(Path.of("a", "b", "c"));
        assertThat(value("Files.exists(\"" + tmp.toString().replace("\\", "\\\\") + "\")")).isEqualTo(true);
        assertThat(value("Character.isDigit(\"7\")")).isEqualTo(true);
        assertThat(value("LocalDate.of(2024, \"MARCH\", 1).monthValue")).isEqualTo(3);
        assertThat(value("Arrays.asList([3, 1, 2]).size()")).isEqualTo(3);
        assertThat(value("Collections.max([3, 9, 2])")).isEqualTo(9);
        assertThat(value("Long.valueOf(5)")).isEqualTo(5L);
        assertThat(value("BigInteger.ONE.add(5)")).isEqualTo(java.math.BigInteger.valueOf(6));
    }

    @Test
    void casts() throws Exception {
        assertThat(value("[long] 5")).isEqualTo(5L);
        assertThat(value("[int] 3.9")).isEqualTo(3);
        assertThat(value("[char] 65")).isEqualTo('A');
        assertThat(value("[Path] \"a/b\"")).isEqualTo(Path.of("a/b"));
        run("$l = List.of(1)");
        assertThat(value("[java.util.List] $l")).isEqualTo(List.of(1));
        run("[java.util.ArrayList] $l");
        assertThat(errors).singleElement().asString().contains("conversion impossible", "ArrayList");
    }

    @Test
    void blocksBecomeFunctionalInterfaces() throws Exception {
        run("$l = List.of(\"apple\", \"banana\", \"kiwi\")");
        assertThat(run("$l.stream().filter({ $_.length() > 4 }).map({ $_.toUpperCase() }).toList()"))
                .isEqualTo("APPLE\nBANANA\n");
        assertThat(run("$m = new java.util.ArrayList($l); $m.sort({ $a.length() - $b.length() }); $m"))
                .isEqualTo("kiwi\napple\nbanana\n");
        assertThat(value("$l.stream().reduce(\"\", { $a + $b.charAt(0) })")).isEqualTo("abk");
        assertThat(value("Stream.iterate(1, { $_ * 2 }).limit(5).toList()")).isEqualTo(List.of(1, 2, 4, 8, 16));
        assertThat(value("Optional.empty().orElseGet({ \"vide\" })")).isEqualTo("vide");
        assertThat(value("$l.stream().anyMatch({ $_.startsWith(\"k\") })")).isEqualTo(true);
        run("$l.stream().filter({ $_.length() }).toList()");
        assertThat(errors).singleElement().asString().contains("le bloc doit renvoyer un booléen");
    }

    @Test
    void javaExceptionsAreShortErrorsKeptInErrors() throws Exception {
        run("Integer.parseInt(\"x\")");
        assertThat(errors).containsExactly("java.lang.NumberFormatException : For input string: \"x\"");
        assertThat(run("$errors[0].class.simpleName")).isEqualTo("NumberFormatException\n");
        run("$debug = true");
        run("Integer.parseInt(\"y\")");
        assertThat(errors).singleElement().asString().contains("For input string: \"y\"", "at java.base/");
    }

    @Test
    void javaExpressionsInPipelinesAndBlocks() throws Exception {
        assertThat(run("java.util.List.of(\"apple\", \"banana\", \"orange\") | filter { $_.contains(\"b\") }"))
                .isEqualTo("banana\n");
        assertThat(run("items | filter { List.of(\"item1\", \"item3\").contains($_.name) } | count")).isEqualTo("2\n");
        Files.createDirectories(tmp.resolve("sous"));
        Files.writeString(tmp.resolve("f.txt"), "x");
        assertThat(run("new java.io.File(\"" + tmp.toString().replace("\\", "\\\\")
                + "\").listFiles() | filter { $_.directory }")).contains("sous").doesNotContain("f.txt");
        assertThat(value("\"$(Math.max(1, 2)) et $(items -n 1).name\"")).isEqualTo("2 et Item[name=item1, size=10].name");
    }

    @Test
    void commandsStayCommands() throws Exception {
        run("java -version");
        assertThat(errors).containsExactly("commande inconnue : java");
        run("notepad.exe x");
        assertThat(errors).containsExactly("commande inconnue : notepad.exe");
    }

    @Test
    void unknownNamesAndMembers() throws Exception {
        run("java.utl.List.of(1)");
        assertThat(errors).containsExactly("classe introuvable : java.utl.List");
        run("Math.nope(1)");
        assertThat(errors).containsExactly("Math n'a pas de méthode statique nope()");
        run("Math.max(\"a\", 1)");
        assertThat(errors).singleElement().asString().contains("aucune surcharge de max", "max(int, int)");
        run("new Runnable()");
        assertThat(errors).singleElement().asString().contains("classe abstraite ou interface");
        run("$x = Math.NOPE");
        assertThat(errors).containsExactly("Math n'a pas de champ statique NOPE");
    }

    @Test
    void systemExitAndRefusedCalls() throws Exception {
        run("System.setOut(null)");
        assertThat(errors).singleElement().asString().contains("System.setOut est refusé");
        run("System.exit(4)");
        assertThat(session.exitRequest()).hasValue(4);
    }

    @Test
    void helpForJavaClasses() throws Exception {
        assertThat(run("help java.util.List")).contains("interface java.util.List", "Méthodes statiques :",
                "of(Object...) → List", "Méthodes :", "size() → int");
        assertThat(run("help StringBuilder")).contains("classe java.lang.StringBuilder", "Constructeurs :",
                "new StringBuilder(String)");
        assertThat(run("help Math")).contains("static PI : double");
        run("help Nope");
        assertThat(errors).containsExactly("help : commande ou classe inconnue : Nope");
    }

    @Test
    void cancellationIsCooperativeInJavaCallbacks() throws Exception {
        var runner = Thread.ofPlatform().start(() -> {
            try {
                interpreter.execute("Stream.iterate(0, { $_ + 1 }).forEach({ $_ })");
            } catch (Exception _) {
                // attendu : annulation
            }
        });
        Thread.sleep(300);
        runner.interrupt();
        runner.join(5_000);
        assertThat(runner.isAlive()).isFalse();
    }
}
