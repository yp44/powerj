package io.powerj.core.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;

/** Perf mode's Flight Recorder (FR-78): what a recorded line says about CPU, allocation, waits and GC. */
@Timeout(60)
class PerfProfileTest {

    /** Sink so that the JIT cannot remove the work. */
    static volatile Object sink;

    @CmdletInfo(name = "spin", summary = "Burns CPU for some milliseconds, then emits one object")
    static final class Spin implements Cmdlet<Spin.Params, Void, Integer> {

        record Params(@Option(shortName = 'n') int millis) { }

        @Override
        public void begin(Params params, CmdletContext<Integer> context) {
            long end = System.nanoTime() + params.millis() * 1_000_000L;
            long x = 1;
            while (System.nanoTime() < end) {
                x = x * 31 + burn(x);
            }
            sink = x;
            context.emit(1);
        }

        static long burn(long x) {
            return Long.rotateLeft(x ^ 0x9E3779B97F4A7C15L, 7) * 13;
        }
    }

    @CmdletInfo(name = "nap", summary = "Sleeps, then passes the objects on")
    static final class Nap implements Cmdlet<Nap.Params, Integer, Integer> {

        record Params(@Option(shortName = 'n') int millis) { }

        @Override
        public void process(Params params, Integer input, CmdletContext<Integer> context) throws Exception {
            Thread.sleep(params.millis());
            context.emit(input);
        }
    }

    @CmdletInfo(name = "churn", summary = "Allocates arrays, then passes the objects on")
    static final class Churn implements Cmdlet<Churn.Params, Integer, Integer> {

        record Params(@Option(shortName = 'n') int megabytes) { }

        @Override
        public void process(Params params, Integer input, CmdletContext<Integer> context) {
            for (int i = 0; i < params.megabytes(); i++) {
                sink = new byte[1 << 20];
            }
            context.emit(input);
        }
    }

    @TempDir
    Path tmp;

    private Interpreter interpreter;

    @BeforeEach
    void setUp() throws Exception {
        var session = new Session(tmp, tmp, new HashMap<>(System.getenv()));
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(new StringWriter(), true),
                new ArrayList<String>()::add, false), Map.of(),
                CmdletRegistry.of(List.of(new Spin(), new Nap(), new Churn())));
    }

    private PerfProfile record(String line, int keep) throws Exception {
        PerfProfiler.Recording recording = PerfProfiler.start();
        assertThat(recording).isNotNull();
        try {
            interpreter.execute(line);
            return recording.finish(tmp.resolve("perf"), keep);
        } finally {
            recording.close();
        }
    }

    @Test
    void cpuIsAttributedToTheStageThatBurnsIt() throws Exception {
        PerfProfile profile = record("spin -n 400 | nap -n 10 | churn -n 8", 1);

        assertThat(profile.stages()).extracting(PerfProfile.StageProfile::command)
                .containsExactly("spin", "nap", "churn");
        var spin = profile.stages().getFirst();
        assertThat(spin.samples()).isGreaterThan(30);
        assertThat(spin.hot()).isNotEmpty();
        assertThat(spin.hot().getFirst().method()).contains("Spin");
        assertThat(spin.hot().getFirst().line()).isPositive();
        assertThat(profile.samples()).isGreaterThanOrEqualTo(spin.samples());
        assertThat(profile.cpu()).isGreaterThan(java.time.Duration.ofMillis(100));
    }

    @Test
    void allocationsAndWaitsGoToTheirStage() throws Exception {
        PerfProfile profile = record("spin -n 20 | nap -n 150 | churn -n 64", 1);

        var nap = profile.stages().get(1);
        var churn = profile.stages().get(2);
        assertThat(churn.allocated()).isGreaterThan(8L << 20);
        assertThat(churn.sites()).isNotEmpty();
        assertThat(churn.sites().getFirst().site()).contains("Churn");
        assertThat(nap.waited()).isGreaterThan(java.time.Duration.ofMillis(50));
        assertThat(nap.waits()).extracting(PerfProfile.Wait::cause).contains("sleep");
        assertThat(nap.samples()).isLessThan(profile.stages().getFirst().samples() + 30);
        assertThat(profile.allocated()).isGreaterThanOrEqualTo(churn.allocated());
    }

    @Test
    void garbageCollectionsAreCounted() throws Exception {
        PerfProfiler.Recording recording = PerfProfiler.start();
        PerfProfile profile;
        try {
            interpreter.execute("spin -n 10 | churn -n 4");
            System.gc();
            profile = recording.finish(tmp.resolve("perf"), 0);
        } finally {
            recording.close();
        }
        assertThat(profile.gc().count()).isPositive();
        assertThat(profile.gc().after()).isPositive();
    }

    @Test
    void aShortLineIsFlaggedAsIndicative() throws Exception {
        PerfProfile profile = record("spin -n 1", 0);
        assertThat(profile.low()).isTrue();
    }

    @Test
    void theRecordingIsKeptOrDeletedAccordingToTheVariable() throws Exception {
        Path dir = tmp.resolve("perf");
        assertThat(record("spin -n 1", 0).file()).isNull();
        assertThat(dir).isEmptyDirectory();

        PerfProfile kept = record("spin -n 1", 1);
        assertThat(kept.file()).exists();
        Thread.sleep(5);
        PerfProfile newest = record("spin -n 1", 1);
        assertThat(newest.file()).exists();
        assertThat(kept.file()).doesNotExist();

        Thread.sleep(5);
        record("spin -n 1", 3);
        Thread.sleep(5);
        PerfProfile last = record("spin -n 1", 3);
        try (var files = Files.list(dir)) {
            assertThat(files).hasSize(3).contains(last.file());
        }
    }

    @Test
    void theNumberOfFilesToKeepComesFromTheEnvironment() {
        assertThat(PerfProfiler.keep(Map.of())).isZero();
        assertThat(PerfProfiler.keep(Map.of(PerfFiles.VARIABLE, "0"))).isZero();
        assertThat(PerfProfiler.keep(Map.of(PerfFiles.VARIABLE, "1"))).isEqualTo(1);
        assertThat(PerfProfiler.keep(Map.of(PerfFiles.VARIABLE, " 7 "))).isEqualTo(7);
        assertThat(PerfProfiler.keep(Map.of(PerfFiles.VARIABLE, "many"))).isZero();
        assertThat(PerfProfiler.keep(Map.of(PerfFiles.VARIABLE, "-2"))).isZero();
    }
}
