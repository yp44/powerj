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
                    total += String.valueOf(PropertyAccess.read(item, name)).length();
                }
            }
            return total;
        });

        assertThat(length).isPositive();
    }
}
