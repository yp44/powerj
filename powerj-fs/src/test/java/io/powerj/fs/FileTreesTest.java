package io.powerj.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileTreesTest {

    private static final boolean WINDOWS = System.getProperty("os.name").startsWith("Windows");

    @TempDir
    Path tmp;

    private Path tree;
    private final List<Path> removed = new ArrayList<>();
    private final List<Path> errorPaths = new ArrayList<>();
    private final List<IOException> errors = new ArrayList<>();

    @BeforeEach
    void tree() throws Exception {
        tree = tmp.resolve("t");
        Files.createDirectories(tree.resolve("a/b/c"));
        Files.createDirectories(tree.resolve("empty"));
        Files.writeString(tree.resolve("root.txt"), "r");
        Files.writeString(tree.resolve("a/one.txt"), "1");
        Files.writeString(tree.resolve("a/b/two.txt"), "2");
        Files.writeString(tree.resolve("a/b/c/three.txt"), "3");
        Path hidden = Files.writeString(tree.resolve(".hidden"), "h");
        if (WINDOWS) {
            Files.setAttribute(hidden, "dos:hidden", true);
        }
    }

    private boolean delete(Path root, int parallelism, boolean dryRun) {
        return FileTrees.delete(root, parallelism, dryRun, entry -> removed.add(entry.path()), this::error);
    }

    private void error(Path path, IOException e) {
        errorPaths.add(path);
        errors.add(e);
    }

    private static List<Path> entries(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.toList();
        }
    }

    /** A wide tree: many files and folders, to keep several workers busy. */
    private Path wide(String name) throws IOException {
        Path root = tmp.resolve(name);
        for (int d = 0; d < 10; d++) {
            Path dir = Files.createDirectories(root.resolve("d" + d + "/sub"));
            for (int f = 0; f < 20; f++) {
                Files.writeString(dir.resolve("f" + f), "x" + f);
                Files.writeString(dir.getParent().resolve("g" + f), "y" + f);
            }
        }
        return root;
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 8})
    void deleteRemovesTheWholeTreeWithHiddenFiles(int parallelism) throws Exception {
        List<Path> before = entries(tree);

        assertThat(delete(tree, parallelism, false)).isTrue();

        assertThat(tree).doesNotExist();
        assertThat(errors).isEmpty();
        assertThat(removed).containsExactlyInAnyOrderElementsOf(before);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 8})
    void eachFolderIsRemovedAfterItsContent(int parallelism) throws Exception {
        Path root = wide("w");
        List<Path> before = entries(root);

        assertThat(delete(root, parallelism, false)).isTrue();

        assertThat(removed).containsExactlyInAnyOrderElementsOf(before);
        for (Path entry : before) {
            int position = removed.indexOf(entry);
            assertThat(removed.subList(position + 1, removed.size()))
                    .noneMatch(later -> later.startsWith(entry) && !later.equals(entry));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 8})
    void dryRunReportsTheSameEntriesAndRemovesNothing(int parallelism) throws Exception {
        List<Path> before = entries(tree);

        assertThat(delete(tree, parallelism, true)).isTrue();

        assertThat(entries(tree)).containsExactlyInAnyOrderElementsOf(before);
        assertThat(removed).containsExactlyInAnyOrderElementsOf(before);
        assertThat(removed.getLast()).isEqualTo(tree);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 8})
    void aSingleFileIsRemoved(int parallelism) {
        Path file = tree.resolve("root.txt");

        assertThat(delete(file, parallelism, false)).isTrue();

        assertThat(file).doesNotExist();
        assertThat(removed).containsExactly(file);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 8})
    void aMissingRootIsAnError(int parallelism) {
        Path missing = tmp.resolve("missing");

        assertThat(delete(missing, parallelism, false)).isFalse();

        assertThat(errorPaths).containsExactly(missing);
        assertThat(removed).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 8})
    void linksAreRemovedNotFollowed(int parallelism) throws Exception {
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("keep.txt"), "k");
        try {
            Files.createSymbolicLink(tree.resolve("link"), outside);
            Files.createSymbolicLink(tree.resolve("broken"), tmp.resolve("nowhere"));
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links not available: " + e);
        }

        assertThat(delete(tree, parallelism, false)).isTrue();

        assertThat(tree).doesNotExist();
        assertThat(outside.resolve("keep.txt")).exists();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 8})
    void anEntryThatCannotBeRemovedIsReportedAndTheRestIsRemoved(int parallelism) throws Exception {
        assumeTrue(!WINDOWS && !"root".equals(System.getProperty("user.name")), "permissions are not enforced");
        Path locked = tree.resolve("a/b");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertThat(delete(tree, parallelism, false)).isFalse();

            assertThat(tree.resolve("root.txt")).doesNotExist();
            assertThat(tree.resolve("empty")).doesNotExist();
            assertThat(locked.resolve("two.txt")).exists();
            assertThat(errors).hasAtLeastOneElementOfType(AccessDeniedException.class)
                    .hasAtLeastOneElementOfType(DirectoryNotEmptyException.class);
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 8})
    void copyGivesAnIdenticalTree(int parallelism) throws Exception {
        FileTime time = FileTime.fromMillis(1_600_000_000_000L);
        Files.setLastModifiedTime(tree.resolve("a/b/two.txt"), time);
        Path copy = tmp.resolve("copy");

        assertThat(FileTrees.copy(tree, copy, parallelism, false, this::error)).isTrue();

        assertThat(errors).isEmpty();
        assertThat(entries(copy).stream().map(copy::relativize).toList())
                .containsExactlyInAnyOrderElementsOf(entries(tree).stream().map(tree::relativize).toList());
        assertThat(copy.resolve("a/b/c/three.txt")).hasContent("3");
        assertThat(Files.getLastModifiedTime(copy.resolve("a/b/two.txt"))).isEqualTo(time);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 8})
    void copyOfAWideTree(int parallelism) throws Exception {
        Path root = wide("w");
        Path copy = tmp.resolve("copy");

        assertThat(FileTrees.copy(root, copy, parallelism, false, this::error)).isTrue();

        assertThat(errors).isEmpty();
        assertThat(entries(copy)).hasSameSizeAs(entries(root));
        assertThat(copy.resolve("d9/sub/f19")).hasContent("x19");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 8})
    void linksAreCopiedAsLinks(int parallelism) throws Exception {
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        try {
            Files.createSymbolicLink(tree.resolve("link"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links not available: " + e);
        }
        Path copy = tmp.resolve("copy");

        assertThat(FileTrees.copy(tree, copy, parallelism, false, this::error)).isTrue();

        assertThat(Files.isSymbolicLink(copy.resolve("link"))).isTrue();
        assertThat(Files.readSymbolicLink(copy.resolve("link"))).isEqualTo(outside);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 8})
    void copyWithoutReplaceReportsEachExistingFile(int parallelism) throws Exception {
        Path copy = tmp.resolve("copy");
        Files.createDirectories(copy.resolve("a"));
        Files.writeString(copy.resolve("a/one.txt"), "old");

        // the existing root folder is reported; with replace it is merged
        assertThat(FileTrees.copy(tree, copy, parallelism, false, this::error)).isFalse();
        assertThat(errors).singleElement().isInstanceOf(FileAlreadyExistsException.class);

        errors.clear();
        assertThat(FileTrees.copy(tree.resolve("a/one.txt"), copy.resolve("a/one.txt"), parallelism, false,
                this::error)).isFalse();
        assertThat(errors).singleElement().isInstanceOf(FileAlreadyExistsException.class);
        assertThat(copy.resolve("a/one.txt")).hasContent("old");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 8})
    void copyWithReplaceOverwritesAndMerges(int parallelism) throws Exception {
        Path copy = tmp.resolve("copy");
        Files.createDirectories(copy.resolve("a"));
        Files.writeString(copy.resolve("a/one.txt"), "old");
        Files.writeString(copy.resolve("a/mine.txt"), "mine");

        assertThat(FileTrees.copy(tree, copy, parallelism, true, this::error)).isTrue();

        assertThat(copy.resolve("a/one.txt")).hasContent("1");
        assertThat(copy.resolve("a/mine.txt")).hasContent("mine");
        assertThat(copy.resolve("a/b/c/three.txt")).hasContent("3");
    }

    @Test
    void anInterruptStopsAParallelRemovalAndItsThreads() throws Exception {
        Path root = wide("w");
        CountDownLatch first = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                FileTrees.delete(root, 4, true, entry -> {
                    first.countDown();
                    try {
                        Thread.sleep(50); // a slow consumer: the work waits on the queue
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, this::error);
            } catch (Throwable e) {
                thrown.set(e);
            }
        });
        caller.start();
        first.await();
        caller.interrupt();
        caller.join(10_000);

        assertThat(caller.isAlive()).isFalse();
        assertThat(thrown.get()).isInstanceOf(CancellationException.class);
    }

    @Test
    void anExceptionOfAConsumerStopsTheWorkAndIsThrownBack() throws Exception {
        Path root = wide("w");

        assertThatThrownBy(() -> FileTrees.delete(root, 4, true, entry -> {
            throw new IllegalStateException("stop");
        }, this::error)).isInstanceOf(IllegalStateException.class).hasMessage("stop");
    }

    @Test
    void parallelismMustBeAtLeastOne() {
        assertThatThrownBy(() -> FileTrees.delete(tree, 0, false, entry -> { }, this::error))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileTrees.copy(tree, tmp.resolve("c"), 0, false, this::error))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
