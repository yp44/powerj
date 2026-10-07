package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
            // Sous Windows, c'est l'attribut « caché » qui compte, pas le point initial.
            Files.setAttribute(cache, "dos:hidden", true);
        }
        context = new TestContext<>(tmp);
    }

    private List<String> names(Ls.Params params) {
        new Ls().begin(params, context);
        return context.emitted.stream().map(FileEntry::name).toList();
    }

    private static Ls.Params params(List<String> paths, boolean all, boolean recurse, String filter,
                                    boolean dirs, boolean files) {
        return new Ls.Params(paths, all, recurse, filter, dirs, files);
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
    void recursiveWithCaseInsensitiveFilter() {
        assertThat(names(params(List.of(), false, true, "*.txt", false, false))).containsExactly("notes.TXT", "b.txt");
        context.emitted.clear();
        assertThat(names(params(List.of(), false, true, null, false, false)))
                .containsExactly("src", "main", "Main.java", "notes.TXT", "A.java", "b.txt");
    }

    @Test
    void dirsOrFilesOnly() {
        assertThat(names(params(List.of(), false, true, null, true, false))).containsExactly("src", "main");
        context.emitted.clear();
        assertThat(names(params(List.of("src"), false, false, null, false, true))).containsExactly("notes.TXT");
        assertThatThrownBy(() -> new Ls().begin(params(List.of(), false, false, null, true, true), context))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void explicitPathsAndWildcards() {
        assertThat(names(params(List.of("src/main", "b.txt"), false, false, null, false, false)))
                .containsExactly("Main.java", "b.txt");
        context.emitted.clear();
        assertThat(names(params(List.of("*.java"), false, false, null, false, false))).containsExactly("A.java");
    }

    @Test
    void missingPathIsANonBlockingError() {
        assertThat(names(params(List.of("absent", "b.txt"), false, false, null, false, false))).containsExactly("b.txt");
        assertThat(context.errors).containsExactly("introuvable : absent");
    }
}
