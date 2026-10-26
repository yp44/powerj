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

/**
 * The first call of a Java method in a fresh JVM, classes loading included (NFR-07: under 50 ms). One call per fork;
 * ten forks.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(10)
@State(Scope.Benchmark)
public class FirstCallBenchmark {

    @Benchmark
    public Object firstStaticCall() {
        return JavaInvoker.invokeStatic(new CallPolicy(code -> { }), Math.class, "max", List.of(3, 7));
    }
}
