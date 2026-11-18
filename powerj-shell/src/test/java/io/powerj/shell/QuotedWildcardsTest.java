package io.powerj.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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
import io.powerj.core.runtime.Interpreter;
import io.powerj.core.runtime.Session;
import io.powerj.core.runtime.ShellIo;

/**
 * Quotes stop wildcards for the cmdlets of the files as for a native program (FR-40, audit v2 R-09): {@code rm "a?.txt"}
 * removes the file of that name and no other. The real cmdlets run through the real interpreter.
 */
class QuotedWildcardsTest {

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = new ArrayList<>();
    private Interpreter interpreter;

    @BeforeEach
    void setUp() throws Exception {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "a file name cannot hold * or ? on Windows");
        Files.writeString(tmp.resolve("a?.txt"), "question");
        Files.writeString(tmp.resolve("ab.txt"), "letters");
        Files.writeString(tmp.resolve("a1.txt"), "digit");
        Files.writeString(tmp.resolve("star*.log"), "star");
        Files.writeString(tmp.resolve("starX.log"), "x");
        Map<String, String> env = new HashMap<>(System.getenv());
        env.remove("POWERJ_GLOB_IGNORE_CASE");
        env.put("POWERJ_TEST", "1");
        Session session = new Session(tmp, tmp, env);
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(out, true), errors::add, false), Map.of(),
                CmdletRegistry.discover());
    }

    private String run(String line) throws Exception {
        out.getBuffer().setLength(0);
        errors.clear();
        interpreter.execute(line);
        return out.toString().replace(System.lineSeparator(), "\n");
    }

    @Test
    void aQuotedWordIsNotAWildcardForRm() throws Exception {
        run("rm \"a?.txt\"");

        assertThat(errors).isEmpty();
        assertThat(tmp.resolve("a?.txt")).doesNotExist();
        assertThat(tmp.resolve("ab.txt")).exists();
        assertThat(tmp.resolve("a1.txt")).exists();
    }

    @Test
    void aFileWhoseNameHoldsAStarCanBeRemovedAlone() throws Exception {
        run("rm \"star*.log\"");

        assertThat(errors).isEmpty();
        assertThat(tmp.resolve("star*.log")).doesNotExist();
        assertThat(tmp.resolve("starX.log")).exists();
    }

    @Test
    void anUnquotedWordStillIsAWildcard() throws Exception {
        run("rm a?.txt");

        assertThat(errors).isEmpty();
        assertThat(tmp.resolve("a?.txt")).doesNotExist();
        assertThat(tmp.resolve("ab.txt")).doesNotExist();
        assertThat(tmp.resolve("a1.txt")).doesNotExist();
    }

    @Test
    void aVariableIsNeverAWildcard() throws Exception {
        run("$name = \"a?.txt\"");
        run("rm $name");

        assertThat(errors).isEmpty();
        assertThat(tmp.resolve("a?.txt")).doesNotExist();
        assertThat(tmp.resolve("ab.txt")).exists();
    }

    @Test
    void inACompoundWordOnlyTheUnquotedTextIsAWildcard() throws Exception {
        Files.createDirectories(tmp.resolve("d?"));
        Files.createDirectories(tmp.resolve("dX"));
        Files.writeString(tmp.resolve("d?/x.txt"), "1");
        Files.writeString(tmp.resolve("d?/y.log"), "2");
        Files.writeString(tmp.resolve("dX/x.txt"), "3");

        run("rm \"d?\"/*.txt");

        assertThat(errors).isEmpty();
        assertThat(tmp.resolve("d?/x.txt")).doesNotExist(); // the unquoted * expanded in the folder named d?
        assertThat(tmp.resolve("d?/y.log")).exists();
        assertThat(tmp.resolve("dX/x.txt")).exists(); // the quoted ? did not
    }

    @Test
    void lsListsOnlyTheFileOfThatName() throws Exception {
        assertThat(run("ls \"a?.txt\" | map { $_.name }")).isEqualTo("a?.txt\n");
        assertThat(errors).isEmpty();
        assertThat(run("ls a?.txt | map { $_.name }")).isEqualTo("a1.txt\na?.txt\nab.txt\n");
    }

    @Test
    void catReadsOnlyTheFileOfThatName() throws Exception {
        assertThat(run("cat \"a?.txt\"")).isEqualTo("question\n");
        assertThat(errors).isEmpty();
    }

    @Test
    void grepSearchesOnlyTheFileOfThatName() throws Exception {
        assertThat(run("grep . \"a?.txt\" | map { $_.text }")).isEqualTo("question\n");
        assertThat(errors).isEmpty();
    }

    @Test
    void cpAndMvTakeAQuotedWordAsAName() throws Exception {
        Files.createDirectories(tmp.resolve("dest"));

        run("cp \"a?.txt\" dest");
        assertThat(errors).isEmpty();
        assertThat(tmp.resolve("dest/a?.txt")).hasContent("question");
        assertThat(tmp.resolve("dest/ab.txt")).doesNotExist();

        run("mv -f \"star*.log\" dest");
        assertThat(errors).isEmpty();
        assertThat(tmp.resolve("dest/star*.log")).exists();
        assertThat(tmp.resolve("starX.log")).exists();
        assertThat(tmp.resolve("dest/starX.log")).doesNotExist();
    }

    @Test
    void touchOfAQuotedWordUpdatesOnlyThatName() throws Exception {
        Files.setLastModifiedTime(tmp.resolve("ab.txt"), java.nio.file.attribute.FileTime.fromMillis(1_000_000L));

        run("touch \"a?.txt\"");

        assertThat(errors).isEmpty();
        assertThat(Files.getLastModifiedTime(tmp.resolve("ab.txt")).toMillis()).isEqualTo(1_000_000L);
    }

    @Test
    void aQuotedWordWithoutMatchIsAMissingFileNotAnEmptyMatch() throws Exception {
        run("rm \"zz?.txt\"");

        assertThat(errors).singleElement().asString().contains("zz?.txt").doesNotContain("no file matches");
    }
}
