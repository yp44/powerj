package io.powerj.fs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A folder whose listing fails in the middle (an unstable network file system): the walk reports it and goes on with
 * the rest, as its Javadoc says (#83). The failure is injected by {@link FaultyFileSystem}.
 */
class FileWalkerListingFailureTest {

    @TempDir
    Path tmp;

    private final FaultyFileSystem faulty = new FaultyFileSystem();
    private final List<String> results = new ArrayList<>();
    private final List<WalkError> errors = new ArrayList<>();

    @BeforeEach
    void tree() throws IOException {
        Files.createDirectories(tmp.resolve("ok"));
        Files.createDirectories(tmp.resolve("bad"));
        Files.createDirectories(tmp.resolve("after"));
        Files.writeString(tmp.resolve("ok/a.txt"), "a");
        Files.writeString(tmp.resolve("ok/b.txt"), "b");
        for (int i = 1; i <= 5; i++) {
            Files.writeString(tmp.resolve("bad/" + i + ".txt"), "x");
        }
        Files.writeString(tmp.resolve("after/c.txt"), "c");
    }

    /** Parallelism, then the number of entries of {@code bad} that are read before the failure. */
    @ParameterizedTest
    @CsvSource({"1, 0", "1, 2", "4, 0", "4, 2"})
    void aFailureWhileListingAFolderIsReportedAndTheWalkGoesOn(int parallelism, int readBeforeTheFailure) {
        faulty.failListing(tmp.resolve("bad"), readBeforeTheFailure);
        Path root = faulty.wrap(tmp);

        FileWalker.builder().parallelism(parallelism).build().walk(root,
                (path, attributes) -> attributes.isDirectory() ? null
                        : root.relativize(path).toString().replace('\\', '/'),
                results::add, errors::add);

        assertThat(results).contains("ok/a.txt", "ok/b.txt", "after/c.txt");
        // the entries read before the failure are kept, the others cannot be known
        assertThat(results.stream().filter(name -> name.startsWith("bad/"))).hasSize(readBeforeTheFailure);
        assertThat(errors).singleElement().satisfies(error -> {
            assertThat(error.path()).hasToString(tmp.resolve("bad").toString());
            assertThat(error.kind()).isEqualTo(WalkError.Kind.IO);
            assertThat(error.message()).contains("injected failure");
        });
    }
}
