package io.powerj.fs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The test file system counts right, or the tests that rely on it prove nothing (#96). */
class FaultyFileSystemTest {

    @TempDir
    Path tmp;

    /**
     * A listing closed twice (which {@link DirectoryStream} allows) ends once: had it counted twice, the two listings that
     * follow would be seen as one at a time, and a parallel work over its bound could pass {@link ParallelismBoundTest}.
     */
    @Test
    void aListingClosedTwiceEndsOnce() throws IOException {
        var faulty = new FaultyFileSystem();
        Path dir = faulty.wrap(Files.createDirectories(tmp.resolve("d")));

        DirectoryStream<Path> first = Files.newDirectoryStream(dir);
        first.close();
        first.close();
        try (DirectoryStream<Path> _ = Files.newDirectoryStream(dir);
             DirectoryStream<Path> _ = Files.newDirectoryStream(dir)) {
            assertThat(faulty.occupancy().max()).isEqualTo(2);
        }
    }
}
