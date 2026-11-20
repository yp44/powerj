package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.api.UsageException;
import io.powerj.testing.TestContext;

class LsTest {

    @TempDir
    Path tmp;

    private TestContext<FileEntry> context;

    @BeforeEach
    void tree() throws Exception {
        Files.createDirectories(tmp.resolve("src/main"));
        Files.writeString(tmp.resolve("b.txt"), "12345");
        Files.writeString(tmp.resolve("A.java"), "class A {}");
        Files.writeString(tmp.resolve("src/main/Main.java"), "x");
        Files.writeString(tmp.resolve("src/notes.TXT"), "y");
        Path cache = Files.writeString(tmp.resolve(".cache"), "z");
        if (System.getProperty("os.name").startsWith("Windows")) {
            // On Windows, it is the "hidden" attribute that counts, not the leading dot.
            Files.setAttribute(cache, "dos:hidden", true);
        }
        context = new TestContext<>(tmp);
    }

    private List<String> names(Ls.Params params) {
        new Ls().begin(params, context);
        return context.emitted.stream().map(FileEntry::name).toList();
    }

    private static Ls.Params params(List<?> paths, boolean all, boolean recurse, String filter,
                                    boolean dirs, boolean files) {
        return new Ls.Params(new java.util.ArrayList<Object>(paths), all, recurse, filter, dirs, files, null, null);
    }

    @Test
    void listsCurrentDirectoryDirectoriesFirstThenAlphabetically() {
        assertThat(names(params(List.of(), false, false, null, false, false))).containsExactly("src", "A.java", "b.txt");
    }

    @Test
    void entriesCarryTypedAttributes() {
        names(params(List.of(), false, false, null, false, false));
        FileEntry b = context.emitted.get(2);
        assertThat(b.size()).isEqualTo(5);
        assertThat(b.dir()).isFalse();
        assertThat(b.ext()).isEqualTo("txt");
        assertThat(b.path()).isEqualTo(tmp.resolve("b.txt").toAbsolutePath());
        FileEntry src = context.emitted.getFirst();
        assertThat(src.dir()).isTrue();
        assertThat(src.size()).isZero();
        assertThat(src.ext()).isEmpty();
    }

    @Test
    void hiddenFilesOnlyWithAll() {
        assertThat(names(params(List.of(), true, false, null, false, false))).contains(".cache");
    }

    @Test
    void theFilterKeepsCaseByDefault() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "the matcher of Windows ignores case");
        assertThat(names(params(List.of(), false, true, "*.txt", false, false))).containsExactly("b.txt");
        context.emitted.clear();
        assertThat(names(params(List.of(), false, true, "*.TXT", false, false))).containsExactly("notes.TXT");
    }

    @Test
    void wildcardsOfAPathKeepCaseByDefault() {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "the matcher of Windows ignores case");
        assertThat(names(params(List.of("src/*.txt"), false, false, null, false, false))).isEmpty();
        assertThat(names(params(List.of("src/*.TXT"), false, false, null, false, false))).containsExactly("notes.TXT");
    }

    @Test
    void theVariableMakesTheFilterAndTheWildcardsIgnoreCase() {
        context.setEnvironment("POWERJ_GLOB_IGNORE_CASE", "true");

        assertThat(names(params(List.of(), false, true, "*.txt", false, false))).containsExactly("notes.TXT", "b.txt");
        context.emitted.clear();
        assertThat(names(params(List.of("src/*.txt"), false, false, null, false, false))).containsExactly("notes.TXT");
    }

    @Test
    void aWrongValueOfTheVariableIsAUsageError() {
        context.setEnvironment("POWERJ_GLOB_IGNORE_CASE", "maybe");

        assertThatThrownBy(() -> names(params(List.of("*.txt"), false, false, null, false, false)))
                .isInstanceOf(UsageException.class).hasMessageContaining("maybe");
        assertThat(names(params(List.of("b.txt"), false, false, null, false, false))).containsExactly("b.txt");
    }

    @Test
    void recursiveWithCaseInsensitiveFilter() {
        context.setEnvironment("POWERJ_GLOB_IGNORE_CASE", "true");
        assertThat(names(params(List.of(), false, true, "*.txt", false, false))).containsExactly("notes.TXT", "b.txt");
        context.emitted.clear();
        context.setEnvironment("POWERJ_GLOB_IGNORE_CASE", "false");
        assertThat(names(params(List.of(), false, true, null, false, false)))
                .containsExactly("src", "main", "Main.java", "notes.TXT", "A.java", "b.txt");
    }

    @Test
    void dirsOrFilesOnly() {
        assertThat(names(params(List.of(), false, true, null, true, false))).containsExactly("src", "main");
        context.emitted.clear();
        assertThat(names(params(List.of("src"), false, false, null, false, true))).containsExactly("notes.TXT");
        assertThatThrownBy(() -> new Ls().begin(params(List.of(), false, false, null, true, true), context))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("--dirs and --files are incompatible");
    }

    @Test
    void explicitPathsAndWildcards() {
        assertThat(names(params(List.of("src/main", "b.txt"), false, false, null, false, false)))
                .containsExactly("Main.java", "b.txt");
        context.emitted.clear();
        assertThat(names(params(List.of("*.java"), false, false, null, false, false))).containsExactly("A.java");
    }

    private static Ls.Params maxDepth(List<?> paths, int depth) {
        return new Ls.Params(new java.util.ArrayList<Object>(paths), false, false, null, false, false, null, depth);
    }

    /** FR-35, FR-18b: --max-depth as in grep; it implies --recurse. */
    @Test
    void maxDepthStopsTheWalk() {
        assertThat(names(maxDepth(List.of(), 1))).containsExactly("src", "A.java", "b.txt");
        context.emitted.clear();
        assertThat(names(maxDepth(List.of(), 2))).containsExactlyInAnyOrder("src", "main", "notes.TXT", "A.java", "b.txt");
        context.emitted.clear();
        assertThat(names(maxDepth(List.of(), 3))).contains("Main.java");
    }

    @Test
    void maxDepthCountsAWildcardMatchAsTheFirstLevel() {
        assertThat(names(maxDepth(List.of("s*"), 1))).containsExactly("src");
        context.emitted.clear();
        assertThat(names(maxDepth(List.of("s*"), 2))).containsExactlyInAnyOrder("src", "main", "notes.TXT");
    }

    @Test
    void aMaxDepthBelowOneIsAUsageError() {
        assertThatThrownBy(() -> names(maxDepth(List.of(), 0))).isInstanceOf(UsageException.class)
                .hasMessageContaining("--max-depth");
    }

    @Test
    void parallelWalkGivesTheSameEntriesInAnyOrder() {
        var parallel = new Ls.Params(List.of(), false, true, null, false, false, 4, null);
        assertThat(names(parallel)).containsExactlyInAnyOrder("src", "main", "Main.java", "notes.TXT", "A.java", "b.txt");
        context.emitted.clear();
        var filtered = new Ls.Params(List.of(), false, true, "*.java", false, true, 4, null);
        assertThat(names(filtered)).containsExactlyInAnyOrder("Main.java", "A.java");
        assertThat(context.errors).isEmpty();
        assertThatThrownBy(() -> new Ls().begin(new Ls.Params(List.of(), false, true, null, false, false, 0, null), context))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--parallel");
    }

    @Test
    void missingPathIsANonBlockingError() {
        assertThat(names(params(List.of("absent", "b.txt"), false, false, null, false, false))).containsExactly("b.txt");
        assertThat(context.errors).containsExactly("not found: absent");
    }

    // --- Links and hidden entries (audit v2, U-06) ---

    private boolean link(String name, Path target) {
        try {
            Files.createSymbolicLink(tmp.resolve(name), target);
            return true;
        } catch (UnsupportedOperationException | java.io.IOException e) {
            return false;
        }
    }

    @Test
    void aBrokenLinkNamedByItselfIsListedWithItsOwnAttributes() {
        org.junit.jupiter.api.Assumptions.assumeTrue(link("broken", tmp.resolve("nowhere")), "no symbolic links");

        names(params(List.of("broken"), false, false, null, false, false));

        assertThat(context.errors).isEmpty();
        assertThat(context.emitted).singleElement().satisfies(e -> {
            assertThat(e.name()).isEqualTo("broken");
            assertThat(e.link()).isTrue();
            assertThat(e.dir()).isFalse();
        });
    }

    @Test
    void aLinkToAFolderIsAFolderAndALinkWhenListedInItsParent() {
        org.junit.jupiter.api.Assumptions.assumeTrue(link("alias", tmp.resolve("src")), "no symbolic links");

        names(params(List.of(), false, false, null, false, false));

        FileEntry alias = context.emitted.stream().filter(e -> e.name().equals("alias")).findFirst().orElseThrow();
        assertThat(alias.link()).isTrue();
        assertThat(alias.dir()).isTrue(); // it shows its target, as before
        assertThat(context.emitted.stream().filter(e -> e.name().equals("src")).findFirst().orElseThrow().link())
                .isFalse();
        assertThat(context.emitted.stream().filter(e -> e.name().equals("b.txt")).findFirst().orElseThrow().link())
                .isFalse();
    }

    @Test
    void aBrokenLinkIsListedInItsParentWithTheLinkFlag() {
        org.junit.jupiter.api.Assumptions.assumeTrue(link("broken", tmp.resolve("nowhere")), "no symbolic links");

        names(params(List.of(), false, false, null, false, false));

        assertThat(context.emitted).filteredOn(e -> e.name().equals("broken")).singleElement()
                .satisfies(e -> assertThat(e.link()).isTrue());
    }

    @Test
    void aWildcardFindsALinkToo() {
        org.junit.jupiter.api.Assumptions.assumeTrue(link("alias.txt", tmp.resolve("b.txt")), "no symbolic links");

        names(params(List.of("alias*"), false, false, null, false, false));

        assertThat(context.emitted).singleElement().satisfies(e -> {
            assertThat(e.link()).isTrue();
            assertThat(e.size()).isEqualTo(5); // the size of its target
        });
    }

    @Test
    void theHiddenFlagIsSetOnHiddenEntriesOnly() {
        names(params(List.of(), true, false, null, false, false));

        assertThat(context.emitted).filteredOn(FileEntry::hidden).extracting(FileEntry::name).containsExactly(".cache");
        assertThat(context.emitted).filteredOn(e -> !e.hidden()).extracting(FileEntry::name)
                .containsExactlyInAnyOrder("src", "A.java", "b.txt");
    }

    @Test
    void theLinkAndHiddenFlagsAreNotDisplayedByDefaultButAreProperties() {
        var display = FileEntry.class.getAnnotation(io.powerj.api.Display.class);

        assertThat(display.columns()).containsExactly("name", "size", "modified", "dir");
        assertThat(java.util.Arrays.stream(FileEntry.class.getRecordComponents()).map(c -> c.getName()))
                .contains("link", "hidden");
    }
}
