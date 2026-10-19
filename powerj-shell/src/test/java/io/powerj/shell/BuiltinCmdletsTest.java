package io.powerj.shell;

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

import io.powerj.core.exec.CmdletRegistry;
import io.powerj.core.exec.Interpreter;
import io.powerj.core.exec.Session;
import io.powerj.core.exec.ShellIo;

/** {@code ls} and {@code env} discovered by ServiceLoader, as in the real shell. */
class BuiltinCmdletsTest {

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = new ArrayList<>();
    private Session session;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(tmp.resolve("docs"));
        Files.writeString(tmp.resolve("notes.txt"), "bonjour");
        Files.writeString(tmp.resolve("docs/spec.md"), "# spec");
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("POWERJ_TEST", "1");
        session = new Session(tmp, tmp, env);
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(out, true), errors::add, false),
                Map.of(), CmdletRegistry.discover());
    }

    private String run(String line) throws Exception {
        out.getBuffer().setLength(0);
        errors.clear();
        interpreter.execute(line);
        return out.toString().replace(System.lineSeparator(), "\n");
    }

    @Test
    void registryFindsTheBuiltinCmdlets() {
        assertThat(interpreter.registry().find("ls")).isPresent();
        assertThat(interpreter.registry().find("env")).isPresent();
    }

    @Test
    void lsDisplaysATable() throws Exception {
        String table = run("ls");
        assertThat(table.lines().toList().getFirst()).matches("name\\s+size\\s+modified\\s+dir");
        assertThat(table).doesNotContain(tmp.toString()); // path is not displayed, but stays a property:
        assertThat(run("(ls)*.path")).contains(tmp.resolve("notes.txt").toString());
        assertThat(table).contains("docs", "notes.txt", "7 B", "true", "false");
    }

    @Test
    void lsReturnsObjects() throws Exception {
        assertThat(run("(ls)*.name")).isEqualTo("docs\nnotes.txt\n");
        run("$f = ls -r --files");
        assertThat(run("$f[0].name")).isEqualTo("spec.md\n");
        assertThat(run("$f[-1].size")).isEqualTo("7\n");
        assertThat(run("$f[0].path.parent")).isEqualTo(tmp.resolve("docs") + "\n");
        assertThat(run("$f[0].ext")).isEqualTo("md\n");
    }

    @Test
    void lsOptionErrorsAndHelp() throws Exception {
        run("ls --recurce");
        assertThat(errors).containsExactly("ls: unknown option --recurce, did you mean --recurse?");
        assertThat(run("ls --help")).contains("ls — Lists files and directories", "-r, --recurse", "Output: FileEntry");
        assertThat(run("which ls")).startsWith("ls → cmdlet ("); // module name: see the packaged binary
    }

    @Test
    void grepSearchesFilesAndFiltersThePipeline() throws Exception {
        Files.writeString(tmp.resolve("docs/pom.xml"), "<a>\n  <version>1.2.3</version>\n</a>\n");
        String table = run("grep version -r");
        assertThat(table.lines().toList().getFirst()).matches("path\\s+line\\s+text");
        assertThat(table).contains(tmp.resolve("docs/pom.xml").toString(), "2", "<version>1.2.3</version>");
        assertThat(run("grep \"<version>(?<v>[^<]+)\" docs/pom.xml -p 4 | map { m -> m.named.v }"))
                .isEqualTo("1.2.3\n");
        assertThat(run("(grep -i BONJOUR notes.txt).groups[0]")).isEqualTo("bonjour\n");
        // pipeline input: files are searched, other objects filtered; no search of the current directory
        assertThat(run("ls -r --files | grep spec | map { m -> m.line }")).isEqualTo("1\n");
        assertThat(run("List.of(\"a fix\", \"b\", \"c FIX\") | grep -i fix")).isEqualTo("a fix\nc FIX\n");
        assertThat(run("(ls | grep --property name ^no).name")).isEqualTo("notes.txt\n");
        assertThat(run("grep --help")).contains("grep — ", "-p, --parallel", "--max-depth", "--encoding");
        assertThat(run("(grep -s [oe] -r | collect).size()")).isEqualTo("3\n"); // notes.txt, spec.md, pom.xml
        assertThat(run("(grep -s -v version -r)*.line")).isEqualTo("0\n0\n");
        assertThat(errors).isEmpty();
        run("grep \"(\"");
        assertThat(errors).singleElement().asString().startsWith("grep: invalid regular expression (");
    }

    @Test
    void catReadsFilesAndPipelineObjects() throws Exception {
        Files.writeString(tmp.resolve("docs/a.txt"), "one\ntwo\n");
        assertThat(run("cat notes.txt docs/a.txt")).isEqualTo("bonjour\none\ntwo\n");
        assertThat(run("(cat notes.txt docs/a.txt -n)*.line")).isEqualTo("1\n2\n3\n");
        assertThat(run("(cat notes.txt docs/a.txt -c).length")).isEqualTo("15\n");
        // the line breaks of the content are kept, on screen and in a redirected file
        assertThat(run("cat docs/a.txt notes.txt -t")).isEqualTo("one\ntwo\nbonjour\n");
        assertThat(run("cat docs/a.txt -c | map { $_.content }")).isEqualTo("one\ntwo\n");
        run("cat docs/a.txt -c | map { $_.content } > copy.txt");
        assertThat(Files.readString(tmp.resolve("copy.txt")).replace(System.lineSeparator(), "\n"))
                .isEqualTo("one\ntwo\n");
        assertThat(run("\"notes.txt\" | cat")).isEqualTo("bonjour\n");
        assertThat(run("ls docs --filter *.txt | cat")).isEqualTo("one\ntwo\n");
        assertThat(run("grep bonjour notes.txt | cat --property path")).isEqualTo("bonjour\n");
        assertThat(run("cat --help")).contains("cat — ", "-l, --lines", "-t, --text", "-n, --numbered", "-c, --content", "--encoding", "--property");
        assertThat(errors).isEmpty();
        run("cat notes.txt -n -c");
        assertThat(errors).containsExactly("cat: only one output at a time among --lines, --text, --numbered, --content");
        run("cat absent.txt");
        assertThat(errors).singleElement().asString().startsWith("cat: not found: ");
    }

    @Test
    void envReadsAndChangesTheSessionEnvironment() throws Exception {
        assertThat(run("(env POWERJ_TEST).value")).isEqualTo("1\n");
        run("env --set POWERJ_AUTRE=deux");
        assertThat(session.environment()).containsEntry("POWERJ_AUTRE", "deux");
        run("env --unset POWERJ_AUTRE");
        assertThat(session.environment()).doesNotContainKey("POWERJ_AUTRE");
        run("env ABSENTE_XYZ");
        assertThat(errors).containsExactly("env: no such variable: ABSENTE_XYZ");
    }

    @Test
    void helpListsCategories() throws Exception {
        assertThat(run("help")).contains("Files", "ls", "System", "env");
    }

    @Test
    void whereWithBlocks() throws Exception {
        assertThat(run("(ls -r | where { $_.name.endsWith(\".md\") && !$_.dir })*.name")).isEqualTo("spec.md\n");
        assertThat(run("(ls | where { $_.dir })*.name")).isEqualTo("docs\n");
        assertThat(run("(ls -r | where { $_.size > 6 && $_.modified > now - 1d })*.name")).isEqualTo("notes.txt\n");
        assertThat(run("(ls -r | where { List.of(\"md\", \"png\").contains($_.ext) })*.name")).isEqualTo("spec.md\n");
        assertThatThrownBy(() -> run("ls | where { $_.size = 1 }")).hasMessageContaining("use ==");
    }

    @Test
    void whereShortForm() throws Exception {
        assertThat(run("(ls -r | where size > 6)*.name")).isEqualTo("notes.txt\n");
        assertThat(run("(ls -r | where ext == md)*.name")).isEqualTo("spec.md\n");
        assertThat(run("(ls -r | where size >= 6)*.name")).isEqualTo("spec.md\nnotes.txt\n");
        assertThat(run("(ls -r | where name != \"docs\" | where dir == false)*.name")).isEqualTo("spec.md\nnotes.txt\n");
        run("ls | where size");
        assertThat(errors).singleElement().asString().contains("where: condition expected");
        run("ls | where size ~ 3");
        assertThat(errors).singleElement().asString().contains("where: condition expected");
    }

    @Test
    void whereErrorsAreNonBlocking() throws Exception {
        assertThat(run("ls | where { $_.size / 0 > 1 }")).isEmpty();
        assertThat(errors).hasSize(2).allSatisfy(e -> assertThat(e).startsWith("where: calculation failed"));
        run("ls | where { $_.size / 0 > 1 } --on-error silent");
        assertThat(errors).isEmpty();
        run("ls | where { $_.size / 0 > 1 } --on-error stop");
        assertThat(errors).singleElement().asString().startsWith("where: calculation failed");
    }

    @Test
    void whereOnEnvAndStrings() throws Exception {
        assertThat(run("(env | where { $_.name.startsWith(\"POWERJ_T\") }).value")).isEqualTo("1\n");
        run("$l = (ls)*.name");
        assertThat(run("$l | where { $_.contains(\"o\") }")).isEqualTo("docs\nnotes.txt\n");
        assertThat(run("where --help")).contains("where — Filters pipeline objects", "ls | where size > 10kb");
        assertThat(run("help")).contains("Filters", "where");
    }

    @Test
    void lambdasAndMap() throws Exception {
        assertThat(run("(ls -r | where { f -> f.name.endsWith(\".md\") && !f.dir })*.name")).isEqualTo("spec.md\n");
        assertThat(run("ls -r | map { f -> f.name + \" : \" + f.name.length() }"))
                .isEqualTo("docs : 4\nspec.md : 7\nnotes.txt : 9\n");
        assertThat(run("ls | map FileEntry::name")).isEqualTo("docs\nnotes.txt\n");
        assertThat(run("ls --files | map { $_.name.toUpperCase() }")).isEqualTo("NOTES.TXT\n");
        assertThat(run("env POWERJ_TEST | map EnvVar::value")).isEqualTo("1\n");
        assertThat(run("ls | map { f -> f.name.split(\"\\\\.\") }")).isEqualTo("docs\nnotes\ntxt\n");
        assertThat(run("ls | map { f -> null }")).isEmpty();
        run("ls | map 42");
        assertThat(errors).singleElement().asString().contains("map: transformation expected");
        run("ls | where { f -> f.name }");
        assertThat(errors).hasSize(2).allSatisfy(e -> assertThat(e).contains("the block must return a boolean"));
        assertThat(run("help")).contains("map");
    }

    @Test
    void collectAlwaysProducesOneList() throws Exception {
        // Always a list: a single result or none is not "unwrapped".
        assertThat(run("(ls -r | where { f -> f.name == \"spec.md\" } | collect).size()")).isEqualTo("1\n");
        assertThat(run("(ls | where { f -> f.name == \"absent\" } | collect).size()")).isEqualTo("0\n");
        run("$l = ls -r --files | collect");
        assertThat(session.variable("l")).isInstanceOf(io.powerj.api.Collected.class);
        assertThat(run("$l.size()")).isEqualTo("2\n");
        // The next stage receives the whole list, as a single object.
        assertThat(run("ls -r | collect | map { l -> l.size() }")).isEqualTo("3\n");
        assertThat(run("ls -r --files | collect | map { l -> l.stream().map(f -> f.name).sorted().toList() }"))
                .isEqualTo("notes.txt\nspec.md\n");
        assertThat(run("ls | collect | where { l -> l.size() > 1 } | map { l -> \"ok\" }")).isEqualTo("ok\n");
        // At the head of a pipeline, a variable is unrolled like any list; on its own, it remains a list.
        assertThat(run("$l | map { f -> f.name } | where { n -> n.endsWith(\".md\") }")).isEqualTo("spec.md\n");
        run("$m = $l");
        assertThat(session.variable("m")).isSameAs(session.variable("l"));
        assertThat(run("$l.stream() | map { f -> f.name } | where { n -> n.endsWith(\".txt\") }")).isEqualTo("notes.txt\n");
        // Displayed at the end of a pipeline as its elements.
        assertThat(run("ls | collect")).contains("docs", "notes.txt");
        assertThat(run("collect --help")).contains("collect — Gathers the pipeline objects into a single list");
        run("ls | collect -x");
        assertThat(errors).containsExactly("collect: unknown option -x");
    }

    @Test
    void sortOrdersByKeys() throws Exception {
        Files.writeString(tmp.resolve("big.txt"), "x".repeat(100));
        assertThat(run("ls --files | sort size --desc | map { f -> f.name }")).isEqualTo("big.txt\nnotes.txt\n");
        assertThat(run("ls | sort dir name | map { f -> f.name }")).isEqualTo("big.txt\nnotes.txt\ndocs\n");
        assertThat(run("ls | sort { $_.name.length() } -d | map { f -> f.name }"))
                .isEqualTo("notes.txt\nbig.txt\ndocs\n");
        assertThat(run("List.of(\"b\", \"B\", \"a\") | sort -i")).isEqualTo("a\nb\nB\n");
        assertThat(run("List.of(10L, 3, 2.5) | sort")).isEqualTo("2.5\n3\n10\n");
        assertThat(errors).isEmpty();
        run("ls | sort bad!");
        assertThat(errors).containsExactly("sort: invalid property name: bad!");
    }

    @Test
    void firstLastAndRange() throws Exception {
        Files.writeString(tmp.resolve("big.txt"), "x".repeat(100));
        assertThat(run("ls | sort name | first | map { f -> f.name }")).isEqualTo("big.txt\n");
        assertThat(run("ls | sort size | last 1 | map { f -> f.name }")).isEqualTo("big.txt\n");
        assertThat(run("List.of(1, 2, 3, 4, 5) | range --from 2 --to 4")).isEqualTo("2\n3\n4\n");
        assertThat(run("ls | sort name | range --from BIG --to DOCS --property name -i | map { f -> f.name }"))
                .isEqualTo("big.txt\ndocs\n");
        assertThat(run("List.of(\"x\", \"BEGIN\", \"1\", \"END\", \"BEGIN\", \"2\", \"END\") "
                + "| range --from { $_ == \"BEGIN\" } --to { $_ == \"END\" } --repeat --exclude")).isEqualTo("1\n2\n");
        assertThat(run("ls | sort name | range --from ^docs$ --property name | map { f -> f.name }"))
                .isEqualTo("docs\nnotes.txt\n");
        assertThat(errors).isEmpty();
        run("ls | range --from Paul");
        assertThat(errors).singleElement().asString().startsWith("range: --from Paul is not a position");
    }

    @Test
    void selectChoosesAndComputesColumns() throws Exception {
        Files.writeString(tmp.resolve("big.txt"), "x".repeat(2048));
        assertThat(run("ls --files | sort name | select name size")).isEqualTo("""
                name         size
                ----         ----
                big.txt    2.0 KB
                notes.txt     7 B
                """);
        assertThat(run("ls --files | select file=name kb={ $_.size / 1kb } | sort kb -d | first | map { r -> r.file }"))
                .isEqualTo("big.txt\n");
        assertThat(run("ls --files | select name kb={ $_.size / 1kb } | where { $_.kb >= 2 } | map { $_.name }"))
                .isEqualTo("big.txt\n");
        assertThat(errors).isEmpty();
        run("ls --files | select nmae");
        assertThat(errors).singleElement().asString().startsWith("select: column nmae: ");
        run("ls | select");
        assertThat(errors).singleElement().asString().startsWith("select: column expected");
    }

    @Test
    void countAndMeasure() throws Exception {
        Files.writeString(tmp.resolve("big.txt"), "x".repeat(2048));
        assertThat(run("ls | count")).isEqualTo("3\n");
        assertThat(run("ls --files | measure size")).isEqualTo("""
                count     sum  average  min     max
                -----     ---  -------  ---     ---
                    2  2.0 KB   1.0 KB  7 B  2.0 KB
                """);
        assertThat(run("(ls --files | measure size).sum")).isEqualTo("2055\n");
        assertThat(run("List.of(1, 2, 3) | measure | map { $_.average }")).isEqualTo("2.0\n");
        assertThat(errors).isEmpty();
    }

    @Test
    void groupByKeys() throws Exception {
        Files.writeString(tmp.resolve("todo.txt"), "x");
        assertThat(run("ls | group dir")).isEqualTo("""
                key    count
                ---    -----
                true       1
                false      2
                """);
        assertThat(run("ls --files | group ext | sort count -d | first | map { $_.key }")).isEqualTo("txt\n");
        assertThat(run("(ls | group dir)[0].items | map { f -> f.name }")).isEqualTo("docs\n");
        assertThat(run("ls | group ext dir -n | map { g -> g.items.size() }")).isEqualTo("0\n0\n");
        assertThat(errors).isEmpty();
        run("ls | group bad!");
        assertThat(errors).containsExactly("group: invalid property name: bad!");
    }

    @Test
    void fileOperations() throws Exception {
        session.configDirectory(Files.createDirectories(tmp.resolve("config")));
        Files.write(tmp.resolve("config/protected-paths"), List.of(tmp.resolve("docs").toString()));
        assertThat(run("mkdir -p out/sub | map { $_.name }")).isEqualTo("sub\n");
        assertThat(run("touch out/a.txt | map { $_.name }")).isEqualTo("a.txt\n");
        assertThat(run("cp notes.txt out/")).isEmpty();
        assertThat(Files.readString(tmp.resolve("out/notes.txt"))).isEqualTo("bonjour");
        assertThat(run("mv out/a.txt out/b.txt -v | map { $_.name }")).isEqualTo("b.txt\n");
        assertThat(run("rm -r -n out | map { $_.action }")).isEqualTo("remove\nremove\nremove\nremove\n");
        assertThat(run("ls out --files | rm -v | map { $_.name }")).isEqualTo("b.txt\nnotes.txt\n");
        assertThat(errors).isEmpty();
        run("rm -r docs");
        assertThat(errors).singleElement().asString().startsWith("rm: protected path: ");
        assertThat(tmp.resolve("docs/spec.md")).exists();
        run("rm");
        assertThat(errors).containsExactly("rm: path expected: rm <path>... or ... | rm");
        assertThat(run("ls docs --files | touch { $_.name } | map { $_.path.parent }")).isEqualTo(tmp + "\n");
        assertThat(tmp.resolve("spec.md")).exists();
        run("touch { $_.name }");
        assertThat(errors).singleElement().asString().startsWith("touch: the block $_.name gives the path of each");
    }

    @Test
    void jsonAndCsv() throws Exception {
        assertThat(run("ls --filter notes.txt | select name size | to-json -c"))
                .isEqualTo("[{\"name\":\"notes.txt\",\"size\":7}]\n");
        run("ls --filter notes.txt | select name size | to-json > files.json");
        assertThat(run("cat files.json | from-json | map { $_.name + \" \" + $_.size }")).isEqualTo("notes.txt 7\n");
        assertThat(run("cat files.json -c | from-json --property content | map { $_.size + 1 }")).isEqualTo("8\n");
        run("ls --filter notes.txt | select name size | to-csv -d \";\" > files.csv");
        assertThat(Files.readAllLines(tmp.resolve("files.csv"))).containsExactly("name;size", "notes.txt;7");
        assertThat(run("cat files.csv | from-csv -d \";\" | map { $_.size + \"!\" }")).isEqualTo("7!\n");
        assertThat(errors).isEmpty();
        run("\"files.json\" | from-json");
        assertThat(errors).singleElement().asString()
                .isEqualTo("from-json: invalid JSON at line 1, column 1: unexpected text: files.json");
    }
}
