package io.powerj.bench;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import io.powerj.core.assist.Completions;
import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.runtime.Interpreter;
import io.powerj.core.runtime.Session;
import io.powerj.core.runtime.ShellIo;

/**
 * One Tab (NFR-02: under 50 ms, imperceptible typing) on lines that exercise each kind of completion, with the real
 * cmdlets and the folder of the build as the current directory.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class CompletionBenchmark {

    @Param({"l", "ls -", "ls | where { $_.", "ls | map { f -> f.name.", "Math.ma", "java.util.Li", "cat src/ma"})
    public String line;

    private Completions completions;

    @Setup
    public void setUp() {
        Path here = Path.of("").toAbsolutePath();
        Map<String, String> environment = new HashMap<>(System.getenv());
        var session = new Session(here, here, environment);
        var interpreter = new Interpreter(session, new ShellIo(new PrintWriter(new StringWriter()), _ -> { }, false),
                Map.of(), CmdletRegistry.discover());
        completions = new Completions(interpreter);
        completions.warmUp();
        completions.complete(line, line.length());
    }

    @Benchmark
    public Completions.Result complete() {
        return completions.complete(line, line.length());
    }
}
