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
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The removal of a tree whose folder cannot be listed to the end: the entries read are removed, the failure is reported,
 * the folder that still holds entries stays, and the rest of the tree is removed (#83; {@code FileTrees} already did, this
 * keeps it so).
 */
class FileTreesListingFailureTest {

    @TempDir
    Path tmp;

    private final FaultyFileSystem faulty = new FaultyFileSystem();

    @BeforeEach
    void tree() throws IOException {
        Files.createDirectories(tmp.resolve("ok"));
        Files.createDirectories(tmp.resolve("bad"));
        Files.writeString(tmp.resolve("ok/a.txt"), "a");
        for (int i = 1; i <= 5; i++) {
            Files.writeString(tmp.resolve("bad/" + i + ".txt"), "x");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void aFolderThatCannotBeListedToTheEndIsReportedAndTheRestIsRemoved(int parallelism) throws IOException {
        faulty.failListing(tmp.resolve("bad"), 2);
        Path root = faulty.wrap(tmp);
        List<String> removed = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        boolean clean = FileTrees.delete(root, parallelism, false,
                entry -> removed.add(root.relativize(entry.path()).toString().replace('\\', '/')),
                (path, e) -> errors.add(root.relativize(path) + ": " + e.getMessage()));

        assertThat(clean).isFalse();
        assertThat(removed).contains("ok/a.txt", "ok");
        assertThat(removed.stream().filter(name -> name.startsWith("bad/"))).hasSize(2);
        assertThat(removed).doesNotContain("bad", ""); // bad still holds entries, so neither it nor the root goes
        assertThat(errors).anyMatch(error -> error.startsWith("bad: ") && error.contains("injected failure"));
        assertThat(tmp.resolve("bad")).isDirectory();
        assertThat(tmp.resolve("ok")).doesNotExist();
    }
}
