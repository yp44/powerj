package io.powerj.fs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A parallel walk, removal or copy never runs more than {@code parallelism} listings, visits, removals or copies at the
 * same time: they hold an open folder or file each (#96). Each operation is slowed down so that those that may overlap
 * do; the highest count seen must then be the parallelism exactly, neither more (the bound) nor less (no overlap: the test
 * would prove nothing).
 */
class ParallelismBoundTest {

    private static final int PARALLELISM = 2;
    private static final Duration DELAY = Duration.ofMillis(1);

    @TempDir
    Path tmp;

    private final FaultyFileSystem faulty = new FaultyFileSystem();
    private final List<String> errors = new ArrayList<>();

    /** A wide tree: 10 folders, each with a subfolder, and 40 files in each pair: 420 entries in all. */
    @BeforeEach
    void tree() throws IOException {
        for (int d = 0; d < 10; d++) {
            Path dir = Files.createDirectories(tmp.resolve("w/d" + d + "/sub"));
            for (int f = 0; f < 20; f++) {
                Files.writeString(dir.resolve("f" + f), "x" + f);
                Files.writeString(dir.getParent().resolve("g" + f), "y" + f);
            }
        }
        faulty.slowDown(DELAY);
    }

    @Test
    void aParallelWalkListsAndVisitsAtMostParallelismEntriesAtATime() {
        Occupancy occupancy = faulty.occupancy();
        List<Path> visited = new ArrayList<>();

        FileWalker.builder().parallelism(PARALLELISM).build().walk(faulty.wrap(tmp.resolve("w")), (path, attributes) -> {
            occupancy.enter();
            try {
                Thread.sleep(DELAY);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                occupancy.exit();
            }
            return path;
        }, visited::add, error -> errors.add(error.toString()));

        assertThat(errors).isEmpty();
        assertThat(visited).hasSize(420);
        assertThat(occupancy.max()).isEqualTo(PARALLELISM);
    }

    @Test
    void aParallelRemovalListsAndRemovesAtMostParallelismEntriesAtATime() {
        boolean clean = FileTrees.delete(faulty.wrap(tmp.resolve("w")), PARALLELISM, false, _ -> { },
                (path, e) -> errors.add(path + ": " + e));

        assertThat(clean).isTrue();
        assertThat(errors).isEmpty();
        assertThat(tmp.resolve("w")).doesNotExist();
        assertThat(faulty.deletions()).isEqualTo(421);
        assertThat(faulty.occupancy().max()).isEqualTo(PARALLELISM);
    }

    @Test
    void aParallelCopyListsCreatesAndCopiesAtMostParallelismEntriesAtATime() {
        boolean clean = FileTrees.copy(faulty.wrap(tmp.resolve("w")), faulty.wrap(tmp.resolve("copy")), PARALLELISM,
                false, (path, e) -> errors.add(path + ": " + e));

        assertThat(clean).isTrue();
        assertThat(errors).isEmpty();
        assertThat(tmp.resolve("copy/d9/sub/f19")).hasContent("x19");
        assertThat(faulty.occupancy().max()).isEqualTo(PARALLELISM);
    }
}
