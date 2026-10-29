package io.powerj.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.nativeexec.Platform;
import io.powerj.core.support.FakeCmdlets;

/** Wildcards in the unquoted words of a native command are replaced by the file names they match (FR-40). */
class NativeWildcardsTest {

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private Session session;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() throws Exception {
        assumeFalse(Platform.isWindows(), "on Windows the program expands its own wildcards");
        for (String name : List.of("b.txt", "a.txt", "c.log", "Notes.TXT", ".hidden.txt")) {
            Files.createFile(tmp.resolve(name));
        }
        Files.createDirectories(tmp.resolve("sub"));
        Files.createFile(tmp.resolve("sub/d.txt"));
        Files.createDirectories(tmp.resolve("my docs"));
        Files.createFile(tmp.resolve("my docs/a note.txt"));
        start(Map.of());
    }

    private void start(Map<String, String> extraEnvironment) {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.remove("POWERJ_GLOB_IGNORE_CASE");
        environment.putAll(extraEnvironment);
        session = new Session(tmp, tmp, environment);
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(out, true), errors::add, false), Map.of(),
                CmdletRegistry.of(FakeCmdlets.all()));
    }

    private String run(String line) throws Exception {
        out.getBuffer().setLength(0);
        errors.clear();
        interpreter.execute(line);
        return out.toString().replace(System.lineSeparator(), "\n");
    }

    @Test
    void anUnquotedWildcardIsReplacedByTheNamesItMatches() throws Exception {
        assertThat(run("^echo *.txt")).isEqualTo("a.txt b.txt\n");
        assertThat(errors).isEmpty();
    }

    @Test
    void aWordThatMatchesNothingIsPassedAsWritten() throws Exception {
        assertThat(run("^echo *.zip")).isEqualTo("*.zip\n");
    }

    @Test
    void aQuotedWordIsNeverExpanded() throws Exception {
        assertThat(run("^echo \"*.txt\"")).isEqualTo("*.txt\n");
    }

    @Test
    void theValueOfAVariableIsNeverExpanded() throws Exception {
        run("$p = \"*.txt\"");
        assertThat(run("^echo $p")).isEqualTo("*.txt\n");
    }

    @Test
    void everyArgumentIsExpandedOnItsOwn() throws Exception {
        assertThat(run("^echo x *.log x?.zip sub/*.txt")).isEqualTo("x c.log x?.zip sub/d.txt\n");
    }

    @Test
    void theNamesAreMatchedFromTheCurrentFolderOfTheSession() throws Exception {
        run("cd sub");
        assertThat(run("^echo *.txt")).isEqualTo("d.txt\n");
        assertThat(run("^echo ../*.log")).isEqualTo("../c.log\n");
    }

    @Test
    void aNameWithASpaceIsOneArgument() throws Exception {
        assertThat(run("^printf \"[%s]\" my*/*.txt")).isEqualTo("[my docs/a note.txt]\n");
    }

    @Test
    void hiddenFilesNeedADotInThePattern() throws Exception {
        assertThat(run("^echo .*.txt")).isEqualTo(".hidden.txt\n");
        assertThat(run("^echo *.txt")).doesNotContain(".hidden");
    }

    @Test
    void aNameThatStartsWithADashIsNotTakenForAnOption() throws Exception {
        Files.createFile(tmp.resolve("-n"));

        assertThat(run("^echo -*")).isEqualTo("./-n\n");
    }

    @Test
    void caseCountsByDefault() throws Exception {
        assertThat(run("^echo *.TXT")).isEqualTo("Notes.TXT\n");
        assertThat(run("^echo notes*")).isEqualTo("notes*\n");
    }

    @Test
    void theVariableThatIgnoresCaseIsReadFromTheSessionEnvironment() throws Exception {
        start(Map.of("POWERJ_GLOB_IGNORE_CASE", "true"));

        assertThat(run("^echo *.txt")).isEqualTo("a.txt b.txt Notes.TXT\n");
        assertThat(run("^echo notes*")).isEqualTo("Notes.TXT\n");
    }

    @Test
    void theVariableIsReadAtEachUse() throws Exception {
        assertThat(run("^echo notes*")).isEqualTo("notes*\n");
        Map<String, String> environment = session.environment();
        synchronized (environment) {
            environment.put("POWERJ_GLOB_IGNORE_CASE", "1");
        }

        assertThat(run("^echo notes*")).isEqualTo("Notes.TXT\n");
    }

    @Test
    void aWrongValueOfTheVariableIsAnErrorOnlyWhereAWildcardIsExpanded() throws Exception {
        start(Map.of("POWERJ_GLOB_IGNORE_CASE", "maybe"));

        assertThat(run("^echo plain")).isEqualTo("plain\n");
        assertThat(errors).isEmpty();

        assertThat(run("^echo *.txt")).isEmpty();
        assertThat(errors).singleElement().asString().contains("POWERJ_GLOB_IGNORE_CASE").contains("maybe");
    }

    @Test
    void aNativeCommandInAPipelineExpandsToo() throws Exception {
        assertThat(run("^echo *.txt | cat")).isEqualTo("a.txt b.txt\n");
    }

    // --- Compound words: pieces glued together (FR-32c, issue #73) ---

    @Test
    void aQuotedFolderFollowedByAWildcardIsOneWord() throws Exception {
        assertThat(run("^printf \"[%s]\" \"my docs\"/*")).isEqualTo("[my docs/a note.txt]\n");
        assertThat(errors).isEmpty();
    }

    @Test
    void aQuotedFolderFollowedByAWildcardNeverReachesTheRoot() throws Exception {
        assertThat(run("^echo \"nowhere\"/*")).isEqualTo("nowhere/*\n");
        assertThat(run("^echo \"my docs\"/*.zip")).isEqualTo("my docs/*.zip\n");
        assertThat(run("^echo \"my docs\"/* x")).isEqualTo("my docs/a note.txt x\n");
    }

    @Test
    void aVariableFollowedByAWildcardIsOneWord() throws Exception {
        run("$d = \"my docs\"");
        assertThat(run("^printf \"[%s]\" $d/*.txt")).isEqualTo("[my docs/a note.txt]\n");
        assertThat(run("^echo $d/x")).isEqualTo("my docs/x\n");
    }

    @Test
    void whatAVariableOrAQuotedTextHoldsIsNeverAWildcard() throws Exception {
        run("$s = \"*.t\"");
        assertThat(run("^echo $s\"xt\"")).isEqualTo("*.txt\n");
        assertThat(run("^echo \"*.t\"xt")).isEqualTo("*.txt\n");
    }

    @Test
    void aQuotedStarIsLiteralAndAnUnquotedOneIsAWildcard() throws Exception {
        Files.createFile(tmp.resolve("a*b1"));
        Files.createFile(tmp.resolve("aXb2"));

        assertThat(run("^printf \"[%s]\" \"a*b\"*")).isEqualTo("[a*b1]\n");
        assertThat(run("^printf \"[%s]\" a\"*\"b?")).isEqualTo("[a*b1]\n");
        assertThat(run("^printf \"[%s]\" \"a\"*")).contains("[aXb2]").contains("[a.txt]");
    }

    @Test
    void aQuotedDotCountsAsTheDotOfAHiddenFile() throws Exception {
        assertThat(run("^echo \".h\"*")).isEqualTo(".hidden.txt\n");
    }

    @Test
    void anOptionWithAQuotedValueIsOneArgument() throws Exception {
        assertThat(run("^printf \"[%s]\" --message=\"a b\"")).isEqualTo("[--message=a b]\n");
    }

    @Test
    void plainTextAroundAQuotedTextIsOneArgument() throws Exception {
        assertThat(run("^echo x\"y\"z")).isEqualTo("xyz\n");
    }

    @Test
    void aBlankStillEndsTheWord() throws Exception {
        assertThat(run("^printf \"[%s]\" \"x\" *.zip")).isEqualTo("[x][*.zip]\n");
    }

    @Test
    void theTildeIsTheHomeFolderAtTheStartOfAWordThatIsUnquotedAndFollowedBySlash() throws Exception {
        assertThat(run("^echo ~/\"x\"")).isEqualTo(session.home() + "/x\n");
        assertThat(run("^echo ~\"x\"")).isEqualTo("~x\n");
    }

    @Test
    void theTargetOfARedirectionCanBeACompoundWord() throws Exception {
        run("\"x\" > \"my docs\"/out.txt");

        assertThat(errors).isEmpty();
        assertThat(tmp.resolve("my docs/out.txt")).exists();
    }
}
