package io.powerj.core.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * What a command pays for the read of a property by {@code CmdletContext.readProperty} (review C-26, #130): the engine looks
 * a component up in a table computed once per class, as it does for {@code $_.name}, and calls its accessor.
 */
class PropertyReadCostTest {

    record Item(String name, long size, Instant modified, Path path, boolean dir, String ext, int depth, double ratio) {
    }

    @Test
    void aMillionReadsByExactNameTakeSeconds() {
        var item = new Item("a.txt", 42, Instant.EPOCH, Path.of("a.txt"), false, "txt", 3, 0.5);
        String[] names = {"name", "size", "modified", "path", "dir", "ext", "depth", "ratio"};

        long length = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            long total = 0;
            for (int row = 0; row < 125_000; row++) {
                for (String name : names) {
                    total += String.valueOf(PropertyAccess.property(item, name)).length();
                }
            }
            return total;
        });

        assertThat(length).isPositive();
    }

    /**
     * A getter is found in a table worked out once per class. Found by scanning the public methods (a {@code getMethods()} copy
     * and a name for each) it cost about a microsecond: five million reads then take more than the budget.
     */
    @Test
    void fiveMillionReadsOfAGetterTakeSeconds() {
        Path path = Path.of("/home/user/a.txt");

        long length = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            long total = 0;
            for (int read = 0; read < 5_000_000; read++) {
                total += PropertyAccess.property(path, "fileName").toString().length();
            }
            return total;
        });

        assertThat(length).isPositive();
    }
}
