package io.powerj.cmdlets;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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

/**
 * What {@code to-csv} and {@code to-json} pay for the columns of one object (review C-26): all the columns of a record of
 * eight components, read by {@code Properties.get} as {@code to-csv} reads them, and the record turned into a map as
 * {@code to-json} does. In the package of {@code Properties}, which is not public.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class PropertiesBenchmark {

    /** A record of the width of {@code FileEntry}. */
    public record Item(String name, long size, Instant modified, Path path, boolean dir, String ext, int depth,
                       double ratio) {
    }

    private final Item item = new Item("a.txt", 42, Instant.EPOCH, Path.of("a.txt"), false, "txt", 3, 0.5);
    private final List<String> columns = Properties.columns(item);

    @Benchmark
    public long toCsvColumnsOfOneRow() {
        long length = 0;
        for (String column : columns) {
            length += String.valueOf(Properties.get(item, column)).length();
        }
        return length;
    }

    @Benchmark
    public int toJsonOneRecord() {
        return Properties.of(item).size();
    }
}
