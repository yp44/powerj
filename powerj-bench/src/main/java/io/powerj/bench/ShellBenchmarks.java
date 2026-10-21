package io.powerj.bench;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import io.powerj.core.exec.CmdletRegistry;
import io.powerj.core.exec.Interpreter;
import io.powerj.core.exec.Session;
import io.powerj.core.exec.ShellIo;
import io.powerj.core.lang.Lexer;
import io.powerj.core.lang.Parser;

/**
 * Micro-benchmarks of the shell's hot paths: lexing and parsing (run on each keystroke by the highlighter and the
 * preview), planning (perf mode), and whole pipelines over a generated file tree. Run with:
 * {@code java -jar powerj-bench/target/benchmarks.jar} (see docs/profiling.md).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = {"-XX:+UnlockDiagnosticVMOptions", "-XX:+DebugNonSafepoints"})
public class ShellBenchmarks {

    private static final String LINE = "ls -r --filter *.txt | where { f -> f.size > 10 } | sort name | first 5";

    /** Files in the generated tree. */
    @Param({"200", "5000"})
    int files;

    private Path tree;
    private Interpreter interpreter;

    @Setup
    public void setUp() throws IOException {
        tree = Files.createTempDirectory("powerj-bench");
        for (int i = 0; i < files; i++) {
            Path dir = tree.resolve("d" + i % 20);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("f" + i + ".txt"), "x".repeat(i % 50));
        }
        var session = new Session(tree, tree, Map.of());
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(Writer.nullWriter()), _ -> { }, false),
                Map.of(), CmdletRegistry.discover());
    }

    @TearDown
    public void tearDown() throws IOException {
        try (Stream<Path> paths = Files.walk(tree)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Benchmark
    public void lex(Blackhole hole) {
        hole.consume(Lexer.tokenize(LINE));
    }

    @Benchmark
    public void parse(Blackhole hole) {
        hole.consume(Parser.parse(LINE));
    }

    @Benchmark
    public void plan(Blackhole hole) {
        hole.consume(interpreter.plan(LINE));
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void pipeline() throws InterruptedException {
        interpreter.execute(LINE);
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void walkOnly() throws InterruptedException {
        interpreter.execute("ls -r | count");
    }
}
