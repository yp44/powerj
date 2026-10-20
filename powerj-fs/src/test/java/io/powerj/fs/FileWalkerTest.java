package io.powerj.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileWalkerTest {

    @TempDir
    Path tmp;

    private final List<String> results = new ArrayList<>();
    private final List<WalkError> errors = new ArrayList<>();

    @BeforeEach
    void tree() throws Exception {
        Files.createDirectories(tmp.resolve("a/b/c"));
        Files.writeString(tmp.resolve("root.txt"), "r");
        Files.writeString(tmp.resolve("a/one.txt"), "1");
        Files.writeString(tmp.resolve("a/b/two.txt"), "2");
        Files.writeString(tmp.resolve("a/b/c/three.txt"), "3");
        Path hidden = Files.writeString(tmp.resolve(".hidden"), "h");
        if (System.getProperty("os.name").startsWith("Windows")) {
            Files.setAttribute(hidden, "dos:hidden", true);
        }
    }

    private void walk(FileWalker walker, Path... roots) {
        walker.walk(List.of(roots), (path, attributes) -> attributes.isDirectory() ? null : name(path),
                results::add, errors::add);
    }

    private String name(Path path) {
        return tmp.relativize(path).toString().replace('\\', '/');
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void visitsAllFilesRecursively(int parallelism) {
        walk(FileWalker.builder().parallelism(parallelism).build(), tmp);
        assertThat(results).containsExactlyInAnyOrder("root.txt", "a/one.txt", "a/b/two.txt", "a/b/c/three.txt");
        assertThat(errors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void maxDepthLimitsTheWalk(int parallelism) {
        walk(FileWalker.builder().maxDepth(1).parallelism(parallelism).build(), tmp);
        assertThat(results).containsExactly("root.txt");
        results.clear();
        walk(FileWalker.builder().maxDepth(2).parallelism(parallelism).build(), tmp);
        assertThat(results).containsExactlyInAnyOrder("root.txt", "a/one.txt");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void hiddenEntriesOnlyOnRequest(int parallelism) {
        walk(FileWalker.builder().maxDepth(1).includeHidden(true).parallelism(parallelism).build(), tmp);
        assertThat(results).containsExactlyInAnyOrder("root.txt", ".hidden");
    }

    @Test
    void sequentialWalkIsDepthFirstInTheGivenOrder() {
        List<String> visited = new ArrayList<>();
        FileWalker.builder().order(Comparator.comparing(p -> p.getFileName().toString())).build()
                .walk(tmp, (path, attributes) -> name(path), visited::add, errors::add);
        assertThat(visited).containsExactly("a", "a/b", "a/b/c", "a/b/c/three.txt", "a/b/two.txt", "a/one.txt",
                "root.txt");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void aFileRootIsVisitedItself(int parallelism) {
        walk(FileWalker.builder().parallelism(parallelism).build(), tmp.resolve("a/one.txt"), tmp.resolve("a/b"));
        assertThat(results).containsExactlyInAnyOrder("a/one.txt", "a/b/two.txt", "a/b/c/three.txt");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void missingRootIsANonBlockingError(int parallelism) {
        walk(FileWalker.builder().parallelism(parallelism).build(), tmp.resolve("absent"), tmp.resolve("a/b/c"));
        assertThat(results).containsExactly("a/b/c/three.txt");
        assertThat(errors).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(WalkError.Kind.NOT_FOUND);
            assertThat(e.path()).isEqualTo(tmp.resolve("absent"));
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void visitorIoExceptionIsReportedAndTheWalkGoesOn(int parallelism) {
        FileWalker.builder().parallelism(parallelism).build().walk(tmp, (path, attributes) -> {
            if (path.endsWith("two.txt")) {
                throw new IOException("unreadable");
            }
            return attributes.isDirectory() ? null : name(path);
        }, results::add, errors::add);
        assertThat(results).containsExactlyInAnyOrder("root.txt", "a/one.txt", "a/b/c/three.txt");
        assertThat(errors).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo(WalkError.Kind.IO);
            assertThat(e.message()).isEqualTo("unreadable");
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void uncheckedExceptionOfTheVisitorStopsTheWalk(int parallelism) {
        FileWalker walker = FileWalker.builder().parallelism(parallelism).build();
        assertThatThrownBy(() -> walker.<String>walk(tmp, (path, attributes) -> {
            throw new IllegalStateException("boom");
        }, results::add, errors::add)).isInstanceOf(IllegalStateException.class).hasMessage("boom");
    }

    @Test
    void symbolicLinksToDirectoriesAreNotFollowed() throws Exception {
        try {
            Files.createSymbolicLink(tmp.resolve("loop"), tmp);
        } catch (UnsupportedOperationException | IOException _) {
            return; // no symbolic links on this system (Windows without privilege)
        }
        walk(FileWalker.builder().parallelism(4).build(), tmp);
        assertThat(results).containsExactlyInAnyOrder("root.txt", "a/one.txt", "a/b/two.txt", "a/b/c/three.txt");
    }

    @Test
    void consumersAreCalledInTheCallingThreadAndVisitsInParallel() throws Exception {
        for (int i = 0; i < 50; i++) {
            Files.writeString(tmp.resolve("a/f" + i + ".txt"), "x");
        }
        Thread caller = Thread.currentThread();
        Set<Thread> visitors = ConcurrentHashMap.newKeySet();
        AtomicInteger consumed = new AtomicInteger();
        FileWalker.builder().parallelism(8).build().walk(tmp, (path, attributes) -> {
            visitors.add(Thread.currentThread());
            return path;
        }, _ -> {
            assertThat(Thread.currentThread()).isSameAs(caller);
            consumed.incrementAndGet();
        }, _ -> assertThat(Thread.currentThread()).isSameAs(caller));
        assertThat(consumed).hasValue(50 + 4 + 3); // files + a, a/b, a/b/c
        assertThat(visitors).doesNotContain(caller).allMatch(Thread::isVirtual);
    }

    @Test
    void interruptionCancelsAParallelWalk() {
        FileWalker walker = FileWalker.builder().parallelism(4).build();
        assertThatThrownBy(() -> walker.walk(tmp, (path, attributes) -> path, _ -> Thread.currentThread().interrupt(),
                errors::add)).isInstanceOf(CancellationException.class);
        assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void interruptionCancelsASequentialWalk() {
        FileWalker walker = FileWalker.builder().build();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> walk(walker, tmp)).isInstanceOf(CancellationException.class);
        } finally {
            Thread.interrupted();
        }
        assertThat(results).isEmpty();
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThatThrownBy(() -> FileWalker.builder().maxDepth(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileWalker.builder().parallelism(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void globsIgnoreCase() {
        assertThat(Globs.matcher("*.TXT").matches(Path.of("a.txt"))).isTrue();
        assertThat(Globs.matcher("a?.java").matches(Path.of("ab.java"))).isTrue();
        assertThat(Globs.isPattern("src/*.java")).isTrue();
        assertThat(Globs.isPattern("src/A.java")).isFalse();
    }

    @Test
    void aBrokenLinkIsDescribedByItself() throws Exception {
        try {
            Files.createSymbolicLink(tmp.resolve("broken"), tmp.resolve("missing"));
        } catch (UnsupportedOperationException | IOException _) {
            return; // no symbolic links on this system (Windows without privilege)
        }
        List<Boolean> links = new ArrayList<>();
        FileWalker.builder().maxDepth(1).build().walk(tmp,
                (path, attributes) -> path.getFileName().toString().equals("broken") ? attributes.isSymbolicLink() : null,
                links::add, errors::add);
        assertThat(links).containsExactly(true);
        assertThat(errors).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void anErrorOfTheVisitorStopsTheWalk(int parallelism) {
        var walker = FileWalker.builder().parallelism(parallelism).build();
        assertThatThrownBy(() -> walker.<String>walk(tmp, (path, attributes) -> {
            throw new StackOverflowError("deep");
        }, results::add, errors::add)).isInstanceOf(StackOverflowError.class).hasMessage("deep");
    }

    @Test
    void errorsAreClassified() {
        Path path = tmp.resolve("x");
        assertThat(WalkError.of(path, new java.nio.file.NoSuchFileException("x")).kind()).isEqualTo(WalkError.Kind.NOT_FOUND);
        assertThat(WalkError.of(path, new java.nio.file.AccessDeniedException("x")).kind())
                .isEqualTo(WalkError.Kind.ACCESS_DENIED);
        var other = WalkError.of(path, new IOException((String) null));
        assertThat(other.kind()).isEqualTo(WalkError.Kind.IO);
        assertThat(other.message()).isEmpty();
    }
}
