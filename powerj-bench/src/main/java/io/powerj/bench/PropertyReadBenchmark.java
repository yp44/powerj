package io.powerj.bench;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import io.powerj.core.java.PropertyAccess;

/**
 * What {@code $_.size} costs for each object that flows through {@code where { $_.size > 1mb }} (review C-26): the read of
 * a record component by {@link PropertyAccess#property}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class PropertyReadBenchmark {

    /** A record of the width of {@code FileEntry}. */
    public record Item(String name, long size, Instant modified, Path path, boolean dir, String ext, int depth,
                       double ratio) {
    }

    private final Item item = new Item("a.txt", 42, Instant.EPOCH, Path.of("a.txt"), false, "txt", 3, 0.5);

    @Benchmark
    public Object firstComponent() {
        return PropertyAccess.property(item, "name");
    }

    @Benchmark
    public Object lastComponent() {
        return PropertyAccess.property(item, "ratio");
    }
}
