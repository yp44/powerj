package io.powerj.bench;

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

import io.powerj.core.java.CallPolicy;
import io.powerj.core.java.JavaInvoker;

/** A Java method called from a line, once more than the first time (NFR-07: under 1 ms; it is a few microseconds). */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class JavaCallBenchmark {

    private final CallPolicy policy = new CallPolicy(code -> { });
    private final List<Object> twoInts = List.of(3, 7);
    private final List<Object> none = List.of();

    @Benchmark
    public Object staticMethod() {
        return JavaInvoker.invokeStatic(policy, Math.class, "max", twoInts);
    }

    @Benchmark
    public Object instanceMethod() {
        return JavaInvoker.invokeVirtual(policy, "abc", "toUpperCase", none);
    }
}
