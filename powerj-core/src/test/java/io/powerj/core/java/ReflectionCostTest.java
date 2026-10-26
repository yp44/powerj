package io.powerj.core.java;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * What reading a property and calling a static method cost per object, in a pipeline of a million (review C-26).
 * <p>
 * {@code Class.getRecordComponents()} builds new objects at every call, about 7 µs: read for each object of {@code ls -r
 * | where { $_.size > 1mb }}, it was seconds of reflection per million files. The budgets are two orders of magnitude
 * above what the cached code takes and one order below what the uncached code took, so a slow machine does not break
 * them and the cost cannot come back unnoticed.
 */
class ReflectionCostTest {

    /** A record of the width of {@code FileEntry}. */
    public record Item(String name, long size, Instant modified, Path path, boolean dir, String ext, int depth,
                       double ratio) {
    }

    private static final int READS = 1_000_000;

    @Test
    void aMillionPropertiesOfARecordAreReadInSeconds() {
        var item = new Item("a.txt", 42, Instant.EPOCH, Path.of("a.txt"), false, "txt", 3, 0.5);

        long sum = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            long total = 0;
            for (int i = 0; i < READS; i++) {
                total += (Integer) PropertyAccess.property(item, "depth");
            }
            return total;
        });

        assertThat(sum).isEqualTo(3L * READS);
    }

    @Test
    void aMillionStaticCallsAreMadeInSeconds() {
        var policy = new CallPolicy(code -> { });
        List<Object> args = List.of(3, 7);

        long sum = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            long total = 0;
            for (int i = 0; i < READS / 4; i++) {
                total += (Integer) JavaInvoker.invokeStatic(policy, Math.class, "max", args);
            }
            return total;
        });

        assertThat(sum).isEqualTo(7L * (READS / 4));
    }

    @Test
    void theValueOfAPropertyIsTheOneOfTheRecordWhateverTheCaseOfTheName() {
        var item = new Item("a.txt", 42, Instant.EPOCH, Path.of("a.txt"), true, "txt", 3, 0.5);

        assertThat(PropertyAccess.property(item, "name")).isEqualTo("a.txt");
        assertThat(PropertyAccess.property(item, "SIZE")).isEqualTo(42L);
        assertThat(PropertyAccess.property(item, "Dir")).isEqualTo(true);
        assertThat(PropertyAccess.property(Map.of("k", 1), "k")).isEqualTo(1);
    }
}
