package io.powerj.cmdlets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.powerj.testing.TestContext;

/**
 * What {@code to-csv}, {@code to-json} and {@code join} pay per column of each object (review C-26) on their side: the
 * names come from a view computed once per class ({@code RecordView}), and the values from the context. The cost of the
 * read itself is the engine's ({@code PropertyReadCostTest} in the core).
 */
class PropertiesCostTest {

    record Item(String name, long size, Instant modified, Path path, boolean dir, String ext, int depth, double ratio) {
    }

    private final Properties properties = new Properties(new TestContext<>());

    @Test
    void aMillionColumnsOfRecordsAreReadInSeconds() {
        var item = new Item("a.txt", 42, Instant.EPOCH, Path.of("a.txt"), false, "txt", 3, 0.5);
        var columns = Properties.columns(item);

        long length = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            long total = 0;
            for (int row = 0; row < 125_000; row++) {
                for (String column : columns) {
                    total += String.valueOf(properties.get(item, column)).length();
                }
            }
            return total;
        });

        assertThat(length).isPositive();
    }

    @Test
    void aMillionRecordsAreTurnedIntoMapsInSeconds() {
        var item = new Item("a.txt", 42, Instant.EPOCH, Path.of("a.txt"), false, "txt", 3, 0.5);

        int size = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            int total = 0;
            for (int row = 0; row < 125_000; row++) {
                total += properties.of(item).size();
            }
            return total;
        });

        assertThat(size).isEqualTo(8 * 125_000);
    }
}
