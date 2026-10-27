package io.powerj.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The expansion of the wildcards of a word into file names (specification FR-40). */
class GlobExpanderTest {

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() throws IOException {
        assumeFalse(System.getProperty("os.name", "").startsWith("Windows"), "a shell expands with / on Linux and macOS");
        for (String name : List.of("b.txt", "a.txt", "c.log", "noext", ".hidden.txt", "Notes.TXT", "x1.md", "x2.md", "x3.md")) {
            Files.createFile(dir.resolve(name));
        }
        Files.createDirectories(dir.resolve("src/one"));
        Files.createDirectories(dir.resolve("src/two"));
        Files.createFile(dir.resolve("src/one/pom.xml"));
        Files.createFile(dir.resolve("src/two/pom.xml"));
        Files.createFile(dir.resolve("src/two/other.xml"));
        Files.createDirectories(dir.resolve("my docs"));
        Files.createFile(dir.resolve("my docs/a note.txt"));
    }

    private List<String> expand(String word) {
        return GlobExpander.expand(word, dir, false);
    }

    @Test
    void aWordWithoutWildcardIsReturnedAsWritten() {
        assertThat(expand("a.txt")).containsExactly("a.txt");
        assertThat(expand("missing.txt")).containsExactly("missing.txt");
        assertThat(expand("-n")).containsExactly("-n");
    }

    @Test
    void aStarGivesTheMatchesSortedByName() {
        assertThat(expand("*.txt")).containsExactly("a.txt", "b.txt");
    }

    @Test
    void theOrderIgnoresCaseThenFallsBackOnTheName() {
        assertThat(expand("*.*")).containsExactly("a.txt", "b.txt", "c.log", "Notes.TXT", "x1.md", "x2.md", "x3.md");
    }

    @Test
    void aWordThatMatchesNothingIsReturnedAsWritten() {
        assertThat(expand("*.zip")).containsExactly("*.zip");
        assertThat(expand("src/*.zip")).containsExactly("src/*.zip");
    }

    @Test
    void aQuestionMarkMatchesOneCharacter() {
        assertThat(expand("x?.md")).containsExactly("x1.md", "x2.md", "x3.md");
        assertThat(expand("?.txt")).containsExactly("a.txt", "b.txt");
    }

    @Test
    void aBracketExpressionMatchesOneOfItsCharacters() {
        assertThat(expand("x[12].md")).containsExactly("x1.md", "x2.md");
        assertThat(expand("[a-b].txt")).containsExactly("a.txt", "b.txt");
        assertThat(expand("x[!1].md")).containsExactly("x2.md", "x3.md");
    }

    @Test
    void anUnclosedBracketIsNotAPattern() {
        assertThat(expand("[ab.txt")).containsExactly("[ab.txt");
    }

    @Test
    void anInvalidPatternIsReturnedAsWritten() {
        assertThat(expand("x[3-1].md")).containsExactly("x[3-1].md");
    }

    @Test
    void aHiddenFileNeedsADotInThePattern() {
        assertThat(expand("*.txt")).doesNotContain(".hidden.txt");
        assertThat(expand(".*.txt")).containsExactly(".hidden.txt");
        assertThat(expand(".h*")).containsExactly(".hidden.txt");
    }

    @Test
    void caseIsKeptByDefault() {
        assertThat(expand("*.TXT")).containsExactly("Notes.TXT");
        assertThat(expand("notes*")).containsExactly("notes*");
    }

    @Test
    void caseCanBeIgnored() {
        assertThat(GlobExpander.expand("*.txt", dir, true)).containsExactly("a.txt", "b.txt", "Notes.TXT");
        assertThat(GlobExpander.expand("notes*", dir, true)).containsExactly("Notes.TXT");
    }

    @Test
    void everyElementOfThePathMayHoldAWildcard() {
        assertThat(expand("src/*/pom.xml")).containsExactly("src/one/pom.xml", "src/two/pom.xml");
        assertThat(expand("s*/t*/*.xml")).containsExactly("src/two/other.xml", "src/two/pom.xml");
    }

    @Test
    void aLiteralElementMustExist() {
        assertThat(expand("nowhere/*.txt")).containsExactly("nowhere/*.txt");
        assertThat(expand("src/*/other.xml")).containsExactly("src/two/other.xml");
    }

    @Test
    void aTrailingSlashKeepsTheFoldersOnly() {
        assertThat(expand("*/")).containsExactly("my docs/", "src/");
        assertThat(expand("src/*/")).containsExactly("src/one/", "src/two/");
    }

    @Test
    void theWordKeepsItsPrefix() {
        assertThat(expand("./*.txt")).containsExactly("./a.txt", "./b.txt");
        assertThat(expand("src/../*.txt")).containsExactly("src/../a.txt", "src/../b.txt");
    }

    @Test
    void anAbsolutePatternGivesAbsoluteNames() {
        assertThat(expand(dir.toString() + "/*.txt")).containsExactly(dir + "/a.txt", dir + "/b.txt");
    }

    @Test
    void aNameWithASpaceStaysOneWord() {
        assertThat(expand("*")).contains("my docs");
        assertThat(expand("my*/*.txt")).containsExactly("my docs/a note.txt");
    }

    @Test
    void aNameThatStartsWithADashIsMadeSafe() throws IOException {
        Files.createFile(dir.resolve("-rf"));
        Files.createFile(dir.resolve("--force"));

        assertThat(expand("-*")).containsExactly("./--force", "./-rf");
        assertThat(expand("*")).contains("./-rf", "./--force").doesNotContain("-rf", "--force");
    }

    @Test
    void aFolderThatDoesNotExistMatchesNothing() {
        assertThat(GlobExpander.expand("*.txt", dir.resolve("nowhere"), false)).containsExactly("*.txt");
    }

    @Test
    void aLinkToAFolderIsFollowedInTheMiddleOfAPath() throws IOException {
        Files.createSymbolicLink(dir.resolve("link"), dir.resolve("src"));

        assertThat(expand("link/*/pom.xml")).containsExactly("link/one/pom.xml", "link/two/pom.xml");
    }
}
