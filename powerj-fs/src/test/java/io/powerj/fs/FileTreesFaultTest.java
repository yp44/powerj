package io.powerj.fs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Removal and copy of a tree whose folder cannot be opened, or whose entry cannot be removed or copied: the failure is
 * reported, and the rest of the tree is done, in both modes (#96). The permissions of a real folder would do the same, but
 * not for root, which can read and write everything; a {@link FaultyFileSystem} fails for every user.
 */
class FileTreesFaultTest {

    private static final FileTime DATE = FileTime.fromMillis(1_500_000_000_000L);

    @TempDir
    Path tmp;

    private final FaultyFileSystem faulty = new FaultyFileSystem();
    private final List<String> removed = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private Path root;

    @BeforeEach
    void tree() throws IOException {
        for (int d = 0; d < 3; d++) {
            for (int f = 0; f < 3; f++) {
                Files.createDirectories(tmp.resolve("w/d" + d));
                Files.writeString(tmp.resolve("w/d" + d + "/f" + f), "x" + f);
            }
        }
        root = faulty.wrap(tmp.resolve("w"));
    }

    private String name(Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private void error(Path path, IOException e) {
        errors.add(name(path) + ": " + e.getClass().getSimpleName());
    }

    private boolean delete(int parallelism) {
        return FileTrees.delete(root, parallelism, false, entry -> removed.add(name(entry.path())), this::error);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void aFolderThatCannotBeOpenedIsReportedAndKeptAndTheRestIsRemoved(int parallelism) {
        faulty.failOpening(tmp.resolve("w/d1"));

        assertThat(delete(parallelism)).isFalse();

        // the root is still tried once its other children are done, and holds d1
        assertThat(errors).containsExactlyInAnyOrder("d1: AccessDeniedException", ": DirectoryNotEmptyException");
        assertThat(removed).contains("d0/f0", "d0", "d2/f2", "d2").noneMatch(name -> name.startsWith("d1"));
        assertThat(tmp.resolve("w/d1/f1")).hasContent("x1");
        assertThat(tmp.resolve("w/d0")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void anEntryThatCannotBeRemovedIsReportedAndItsFolderKept(int parallelism) {
        faulty.failOperation(tmp.resolve("w/d2/f1"), new AccessDeniedException("d2/f1"));

        assertThat(delete(parallelism)).isFalse();

        assertThat(errors).contains("d2/f1: AccessDeniedException", "d2: DirectoryNotEmptyException");
        assertThat(removed).contains("d2/f0", "d2/f2", "d0", "d1").doesNotContain("d2/f1", "d2", "");
        assertThat(tmp.resolve("w/d2/f1")).exists();
        assertThat(tmp.resolve("w/d0")).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void aFolderThatCannotBeOpenedIsReportedAndNotCopied(int parallelism) throws IOException {
        faulty.failOpening(tmp.resolve("w/d1"));
        Files.setLastModifiedTime(tmp.resolve("w"), DATE);

        boolean clean = FileTrees.copy(root, faulty.wrap(tmp.resolve("copy")), parallelism, false, this::error);

        assertThat(clean).isFalse();
        assertThat(errors).containsExactly("d1: AccessDeniedException");
        assertThat(tmp.resolve("copy/d0/f0")).hasContent("x0");
        assertThat(tmp.resolve("copy/d2/f2")).hasContent("x2");
        assertThat(tmp.resolve("copy/d1")).doesNotExist();
        // the copy of the root is still finished once its other children are done
        assertThat(Files.getLastModifiedTime(tmp.resolve("copy"))).isEqualTo(DATE);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void aFolderWhoseCopyCannotBeCreatedIsReportedAndTheRestIsCopied(int parallelism) throws IOException {
        faulty.failOperation(tmp.resolve("copy/d1"), new AccessDeniedException("copy/d1"));
        Files.setLastModifiedTime(tmp.resolve("w"), DATE);
        Path copy = faulty.wrap(tmp.resolve("copy"));

        boolean clean = FileTrees.copy(root, copy, parallelism, false,
                (path, e) -> errors.add(copy.relativize(path) + ": " + e.getClass().getSimpleName()));

        assertThat(clean).isFalse();
        assertThat(errors).containsExactly("d1: AccessDeniedException");
        assertThat(tmp.resolve("copy/d1")).doesNotExist();
        assertThat(tmp.resolve("copy/d2/f0")).hasContent("x0");
        // the copy of the root is still finished once its other children are done
        assertThat(Files.getLastModifiedTime(tmp.resolve("copy"))).isEqualTo(DATE);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void aFileThatCannotBeCopiedIsReportedWithThePathOfItsCopy(int parallelism) {
        faulty.failOperation(tmp.resolve("w/d0/f2"), new AccessDeniedException("d0/f2"));
        Path copy = faulty.wrap(tmp.resolve("copy"));

        boolean clean = FileTrees.copy(root, copy, parallelism, false, (path, e) -> errors.add(copy.relativize(path)
                .toString().replace('\\', '/') + ": " + e.getClass().getSimpleName()));

        assertThat(clean).isFalse();
        assertThat(errors).containsExactly("d0/f2: AccessDeniedException");
        assertThat(tmp.resolve("copy/d0/f1")).hasContent("x1");
        assertThat(tmp.resolve("copy/d0/f2")).doesNotExist();
        assertThat(tmp.resolve("copy/d2/f0")).hasContent("x0");
    }
}
