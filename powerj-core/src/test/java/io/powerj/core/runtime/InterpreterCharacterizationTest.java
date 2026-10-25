package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.nativeexec.Platform;
import io.powerj.core.support.FakeCmdlets;

/**
 * Characterization of {@link Interpreter#execute}: what a line displays, which errors it reports, whether it succeeds
 * ({@code $?}) and whether it asks to exit, for about seventy lines that cover the statements, pipelines, chaining,
 * variables, Java expressions, redirections, built-in commands and native commands.
 * <p>
 * The expected values are the behaviour of the code <b>as it was when Phase 0 of the review started</b>, reviewed
 * line by line, not what the specification says. Several are surprising and are kept on purpose (see the notes): the
 * purpose of this test is that the refactoring of the Interpreter (Phase 2) changes none of them by accident. If a
 * later phase changes one on purpose, it changes the line here, in the same commit.
 * <p>
 * Notes on the surprising ones: {@code items --fail | count} succeeds although an error was reported (the status is the
 * one of the last stage); {@code 1 < 2 && 2 < 3} is two statements chained, not a boolean; {@code !true} is a command
 * name; {@code which items} shows {@code ((no module))} with doubled parentheses; a syntax error is thrown to the
 * caller, which is the supervisor of the shell.
 */
class InterpreterCharacterizationTest {

    @TempDir
    Path tmp;

    /**
     * @param lines     the lines run in order in one session, separated by a line feed; {tmp} is the temporary folder
     * @param out       everything written to the output, line feeds normalised
     * @param errors    the error stream, as {@code List.toString()}
     * @param ok        {@code $?} after the last line
     * @param exit      the exit code requested, or {@code null}
     * @param thrown    the exception that reached the caller, or {@code null}
     */
    record Case(String lines, String out, String errors, boolean ok, Integer exit, String thrown) {
        @Override
        public String toString() {
            return lines.replace("\n", " ; ");
        }
    }

    private static Arguments row(String lines, String out, String errors, boolean ok, Integer exit, String thrown) {
        return Arguments.of(new Case(lines, out, errors, ok, exit, thrown));
    }

    static Stream<Arguments> portable() {
        return Stream.of(
            row("items",
                    "name   size\n----   ----\nitem1    10\nitem2    20\nitem3    30\n",
                    "[]", true, null, null),
            row("items -n 2",
                    "name   size\n----   ----\nitem1    10\nitem2    20\n",
                    "[]", true, null, null),
            row("items | filter { $_.size > 10 }",
                    "name   size\n----   ----\nitem2    20\nitem3    30\n",
                    "[]", true, null, null),
            row("items | pick size > 10",
                    "name   size\n----   ----\nitem2    20\nitem3    30\n",
                    "[]", true, null, null),
            row("items | count",
                    "3\n",
                    "[]", true, null, null),
            row("items | eval-each Item::name",
                    "item1\nitem2\nitem3\n",
                    "[]", true, null, null),
            row("items | eval-each { $_.name } | count",
                    "3\n",
                    "[]", true, null, null),
            row("infinite | take 3",
                    "0\n1\n2\ndone\n",
                    "[]", true, null, null),
            row("items --fail",
                    "name   size\n----   ----\nitem1    10\nitem2    20\nitem3    30\n",
                    "[items: problem with item1]", false, null, null),
            row("items --fail | count",
                    "3\n",
                    "[items: problem with item1]", true, null, null),
            row("nosuchcommand",
                    "",
                    "[unknown command: nosuchcommand]", false, null, null),
            row("items | nosuchcommand",
                    "",
                    "[unknown command: nosuchcommand]", false, null, null),
            row("items | filter { $_.size > 10 } | filter { $_.size < 30 }",
                    "name   size\n----   ----\nitem2    20\n",
                    "[]", true, null, null),
            row("items -n 1 ; items -n 2",
                    "name   size\n----   ----\nitem1    10\nname   size\n----   ----\nitem1    10\nitem2    20\n",
                    "[]", true, null, null),
            row("items -n 1 && items -n 2",
                    "name   size\n----   ----\nitem1    10\nname   size\n----   ----\nitem1    10\nitem2    20\n",
                    "[]", true, null, null),
            row("nosuchcommand && items -n 1",
                    "",
                    "[unknown command: nosuchcommand]", false, null, null),
            row("nosuchcommand || items -n 1",
                    "name   size\n----   ----\nitem1    10\n",
                    "[unknown command: nosuchcommand]", true, null, null),
            row("items -n 1 || items -n 2",
                    "name   size\n----   ----\nitem1    10\n",
                    "[]", true, null, null),
            row("items --fail && items -n 1",
                    "name   size\n----   ----\nitem1    10\nitem2    20\nitem3    30\n",
                    "[items: problem with item1]", false, null, null),
            row("$x = 1 + 2\n$x",
                    "3\n",
                    "[]", true, null, null),
            row("$x = items -n 2\n$x.size()",
                    "2\n",
                    "[]", true, null, null),
            row("1 + 2",
                    "3\n",
                    "[]", true, null, null),
            row("\"a\" + \"b\"",
                    "ab\n",
                    "[]", true, null, null),
            row("Math.max(3, 7)",
                    "7\n",
                    "[]", true, null, null),
            row("\"n=$(1 + 2)\"",
                    "n=3\n",
                    "[]", true, null, null),
            row("$l = List.of(3, 1, 2)\n$l | filter { $_ > 1 }",
                    "3\n2\n",
                    "[]", true, null, null),
            row("List.of(3, 1, 2).stream().sorted().toList()",
                    "1\n2\n3\n",
                    "[]", true, null, null),
            row("(items -n 2)*.name",
                    "item1\nitem2\n",
                    "[]", true, null, null),
            row("$undefined",
                    "",
                    "[unknown variable: $undefined]", false, null, null),
            row("Math.NOPE",
                    "",
                    "[Math has no static field NOPE]", false, null, null),
            row("1 +",
                    "",
                    "[]", true, null, "io.powerj.core.lang.SyntaxException: syntax: incomplete expression"),
            row("eval { 1 + 2 }",
                    "3\n",
                    "[]", true, null, null),
            row("eval { $_ * 2 } -w 21",
                    "",
                    "[\"*\" is not possible between String \"21\" and Integer 2]", false, null, null),
            row("items -n 2 > {tmp}/o.txt\nFiles.readString(Path.of(\"{tmp}/o.txt\"))",
                    "name   size\n----   ----\nitem1    10\nitem2    20\n",
                    "[]", true, null, null),
            row("items -n 1 > {tmp}/o.txt\nitems -n 1 >> {tmp}/o.txt\nFiles.readAllLines(Path.of(\"{tmp}/o.txt\")).size()",
                    "6\n",
                    "[]", true, null, null),
            row("items --fail 2> {tmp}/e.txt\nFiles.readString(Path.of(\"{tmp}/e.txt\"))",
                    "name   size\n----   ----\nitem1    10\nitem2    20\nitem3    30\nitems: problem with item1\n",
                    "[]", true, null, null),
            row("cd {tmp}\npwd",
                    "<tmp>\n",
                    "[]", true, null, null),
            row("which items",
                    "items → cmdlet ((no module))\n",
                    "[]", true, null, null),
            row("which nosuchcommand",
                    "",
                    "[which: not found: nosuchcommand]", false, null, null),
            row("import java.security.*\nimport",
                    "java.lang.*\njava.math.*\njava.text.*\njava.util.*\njava.util.function.*\njava.util.stream.*\njava.util.regex.*\njava.util.concurrent.*\njava.io.*\njava.nio.file.*\njava.nio.charset.*\njava.net.*\njava.net.http.*\njava.time.*\njava.time.format.*\njava.security.*\n",
                    "[]", true, null, null),
            row("jobs",
                    "",
                    "[]", true, null, null),
            row("exit 3",
                    "",
                    "[]", true, 3, null),
            row("List.of(1, 2) | count",
                    "2\n",
                    "[]", true, null, null),
            row("\"abc\" | count",
                    "1\n",
                    "[]", true, null, null),
            row("Optional.of(5) | count",
                    "1\n",
                    "[]", true, null, null),
            row("items | count | filter { $_ > 1 }",
                    "3\n",
                    "[]", true, null, null),
            row("items --help",
                    "items — Produces test objects\nUsage: items [options]\n\nOptions:\n  -n, --count <value>  Number of objects\n      --fail           Reports a non-blocking error\n  --on-error <mode>    non-blocking errors: stop, continue (default) or silent\n\nOutput: Item (name, size)\nModule: (no module)\n\nExamples:\n  items -n 2\n",
                    "[]", true, null, null),
            row("items --nosuchoption",
                    "",
                    "[items: unknown option --nosuchoption]", false, null, null),
            row("items -n abc",
                    "",
                    "[items: invalid value for --count: 'abc']", false, null, null),
            row("items | first",
                    "",
                    "[unknown command: first]", false, null, null),
            row("$a = 5\n$a = $a + 1\n$a",
                    "6\n",
                    "[]", true, null, null),
            row("$s = \"x\"\n\"v=$s\"",
                    "v=x\n",
                    "[]", true, null, null),
            row("$m = Map.of(\"k\", 1)\n$m",
                    "key  value\n---  -----\nk    1\n",
                    "[]", true, null, null),
            row("Path.of(\"/a/b\").getFileName()",
                    "b\n",
                    "[]", true, null, null),
            row("[long] 5",
                    "5\n",
                    "[]", true, null, null),
            row("new StringBuilder(\"ab\").reverse()",
                    "ba\n",
                    "[]", true, null, null),
            row("$r = items -n 3\n$r | eval-each { $_.size } | count",
                    "3\n",
                    "[]", true, null, null),
            row("!true",
                    "",
                    "[unknown command: !true]", false, null, null),
            row("1 < 2 && 2 < 3",
                    "true\ntrue\n",
                    "[]", true, null, null),
            row("true ? \"yes\" : \"no\"",
                    "yes\n",
                    "[]", true, null, null),
            row("items -n 2 | eval-each { $_.name.toUpperCase() }",
                    "ITEM1\nITEM2\n",
                    "[]", true, null, null),
            row("1 / 0",
                    "",
                    "[calculation failed: / by zero]", false, null, null));
    }

    /** Native commands: Linux and macOS (the existing tests of the interpreter cover Windows with its own commands). */
    static Stream<Arguments> natives() {
        return Stream.of(
            row("printf \"a\\nb\\n\"",
                    "a\nb\n",
                    "[]", true, null, null),
            row("printf \"a\\nb\\n\" | count",
                    "2\n",
                    "[]", true, null, null),
            row("printf \"b\\na\\n\" | filter { $_ == \"a\" }",
                    "a\n",
                    "[]", true, null, null),
            row("sh -c \"exit 3\"\n$exit",
                    "3\n",
                    "[]", true, null, null),
            row("sh -c \"exit 2\" || items -n 1",
                    "name   size\n----   ----\nitem1    10\n",
                    "[]", true, null, null),
            row("sh -c \"exit 2\" && items -n 1",
                    "",
                    "[]", false, null, null),
            row("sh -c \"echo out; echo err >&2\"",
                    "out\n",
                    "[err]", true, null, null),
            row("^printf x",
                    "x\n",
                    "[]", true, null, null),
            row("items -n 1 | cat",
                    "name   size\n----   ----\nitem1    10\n",
                    "[]", true, null, null),
            row("printf \"x\\ny\\n\" | sh -c cat",
                    "x\ny\n",
                    "[]", true, null, null),
            row("infinite | head -n 2",
                    "0\n1\n",
                    "[]", true, null, null),
            row("$l = printf \"un\\ndeux\\n\"\n$l.size()",
                    "2\n",
                    "[]", true, null, null),
            row("printf \"a\\n\" > {tmp}/n.txt\nFiles.readString(Path.of(\"{tmp}/n.txt\"))",
                    "a\n",
                    "[]", true, null, null),
            row("nosuchnative",
                    "",
                    "[unknown command: nosuchnative]", false, null, null),
            row("sh -c \"exit 1\" | count",
                    "0\n",
                    "[]", true, null, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("portable")
    void portableLines(Case expected) throws Exception {
        verify(expected, Map.of("PATH", ""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("natives")
    void nativeLines(Case expected) throws Exception {
        assumeFalse(Platform.isWindows(), "Unix commands");
        verify(expected, System.getenv());
    }

    private void verify(Case expected, Map<String, String> baseEnvironment) throws Exception {
        String folder = tmp.toString().replace('\\', '/'); // forward slashes are valid everywhere
        Map<String, String> environment = new HashMap<>(baseEnvironment);
        environment.put("HOME", tmp.toString());
        var session = new Session(tmp, tmp, environment);
        var out = new StringWriter();
        List<String> errors = new ArrayList<>();
        var interpreter = new Interpreter(session, new ShellIo(new PrintWriter(out, true), errors::add, false), Map.of(),
                CmdletRegistry.of(FakeCmdlets.all()));

        Throwable thrown = null;
        for (String line : expected.lines().replace("{tmp}", folder).split("\n")) {
            try {
                interpreter.execute(line);
            } catch (Throwable t) {
                thrown = t;
            }
        }

        assertThat(normalise(out.toString())).as("output").isEqualTo(expected.out());
        assertThat(normalise(errors.toString())).as("errors").isEqualTo(expected.errors());
        assertThat(session.lastSucceeded()).as("$?").isEqualTo(expected.ok());
        assertThat(session.exitRequest().isPresent() ? (Integer) session.exitRequest().getAsInt() : null).as("exit")
                .isEqualTo(expected.exit());
        assertThat(thrown == null ? null : thrown.toString()).as("thrown").isEqualTo(expected.thrown());
    }

    private String normalise(String text) {
        return text.replace(System.lineSeparator(), "\n").replace(tmp.toString(), "<tmp>")
                .replace(tmp.toString().replace('\\', '/'), "<tmp>");
    }
}
