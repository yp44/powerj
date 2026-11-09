package io.powerj.cmdlets;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

import io.powerj.api.CmdletContext;
import io.powerj.api.ScriptBlock;
import io.powerj.core.java.PropertyAccess;

/**
 * What {@code to-csv} and {@code to-json} pay for the columns of one object (review C-26): all the columns of a record of
 * eight components, read by {@code Properties.get} as {@code to-csv} reads them, and the record turned into a map as
 * {@code to-json} does. The values are read as the engine reads them for a command ({@code CmdletContext.readProperty},
 * #130), by {@link PropertyAccess#property}. In the package of {@code Properties}, which is not public.
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

    /** The context of the engine, as far as a command reads a property: {@code CmdletRunner.Context.readProperty}. */
    private static final class EngineReading implements CmdletContext<Object> {
        @Override
        public void emit(Object value) {
        }

        @Override
        public void error(String message) {
        }

        @Override
        public Path currentDirectory() {
            return Path.of(".");
        }

        @Override
        public Map<String, String> environment() {
            return Map.of();
        }

        @Override
        public Optional<Object> variable(String name) {
            return Optional.empty();
        }

        @Override
        public boolean cancelled() {
            return false;
        }

        @Override
        public ScriptBlock compile(String expression) {
            throw new UnsupportedOperationException(expression);
        }

        @Override
        public Object readProperty(Object target, String name) {
            return PropertyAccess.property(target, name);
        }
    }

    private final Item item = new Item("a.txt", 42, Instant.EPOCH, Path.of("a.txt"), false, "txt", 3, 0.5);
    private final List<String> columns = Properties.columns(item);
    private final Properties properties = new Properties(new EngineReading());

    @Benchmark
    public long toCsvColumnsOfOneRow() {
        long length = 0;
        for (String column : columns) {
            length += String.valueOf(properties.get(item, column)).length();
        }
        return length;
    }

    @Benchmark
    public int toJsonOneRecord() {
        return properties.of(item).size();
    }
}
