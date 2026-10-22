package io.powerj.core.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.api.Cmdlet;
import io.powerj.api.CmdletContext;
import io.powerj.api.CmdletInfo;
import io.powerj.api.Option;
import io.powerj.api.PerfReport;
import io.powerj.api.StageStat;

/** Perf mode (FR-76): per-stage counters, waits, blocks, phases, native processes, and the summary. */
class PerfTest {

    /** Emits {@code n} numbers, waiting {@code -d} milliseconds before each one. */
    @CmdletInfo(name = "numbers", category = "Test", summary = "Numbers", examples = "numbers 5")
    static final class Numbers implements Cmdlet<Numbers.Params, Void, Integer> {

        record Params(@Option(position = 0) int count, @Option(shortName = 'd') Integer delay) { }

        @Override
        public void begin(Params params, CmdletContext<Integer> context) throws Exception {
            for (int i = 0; i < params.count(); i++) {
                if (params.delay() != null) {
                    Thread.sleep(params.delay());
                }
                context.emit(i);
            }
        }
    }

    /** Lets everything through, waiting {@code -d} milliseconds for each object. */
    @CmdletInfo(name = "slow", category = "Test", summary = "Slow", examples = "numbers 5 | slow -d 1", passThrough = true)
    static final class Slow implements Cmdlet<Slow.Params, Object, Object> {

        record Params(@Option(shortName = 'd') Integer delay) { }

        @Override
        public void process(Params params, Object input, CmdletContext<Object> context) throws Exception {
            if (params.delay() != null) {
                Thread.sleep(params.delay());
            }
            context.emit(input);
        }
    }

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private Session session;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        session = new Session(tmp, tmp, System.getenv());
        List<Cmdlet<?, ?, ?>> cmdlets = new ArrayList<>(FakeCmdlets.all());
        cmdlets.add(new Numbers());
        cmdlets.add(new Slow());
        interpreter = new Interpreter(session, new ShellIo(new PrintWriter(out, true), errors::add, false), Map.of(),
                CmdletRegistry.of(cmdlets));
        interpreter.perfMode(true);
    }

    private String run(String line) throws Exception {
        out.getBuffer().setLength(0);
        errors.clear();
        interpreter.execute(line);
        return out.toString().replace(System.lineSeparator(), "\n");
    }

    private PerfReport last() {
        assertThat(session.perfReports()).isNotEmpty();
        return session.perfReports().getFirst();
    }

    @Test
    void objectsAreCountedPerStage() throws Exception {
        run("items -n 3 | filter { $_.size > 10 } | count");
        List<StageStat> stages = last().stages();
        assertThat(stages).extracting(StageStat::command).containsExactly("items -n 3", "filter {…}", "count");
        assertThat(stages).extracting(StageStat::index).containsExactly(1, 2, 3);
        assertThat(stages.get(0).in()).isNull();
        assertThat(stages.get(0).out()).isEqualTo(3);
        assertThat(stages.get(1).in()).isEqualTo(3);
        assertThat(stages.get(1).out()).isEqualTo(2);
        assertThat(stages.get(2).in()).isEqualTo(2);
        assertThat(stages.get(2).out()).isEqualTo(1);
    }

    @Test
    void aSlowProducerStarvesTheNextStage() throws Exception {
        run("numbers 20 -d 10 | count");
        StageStat producer = last().stages().get(0);
        StageStat counter = last().stages().get(1);
        assertThat(counter.starved()).isGreaterThan(Duration.ofMillis(100));
        assertThat(producer.active()).isGreaterThan(Duration.ofMillis(100));
        assertThat(producer.blocked()).isLessThan(Duration.ofMillis(50));
        assertThat(last().stages()).extracting(StageStat::bottleneck).containsExactly(true, false);
    }

    @Test
    void aSlowConsumerBlocksThePreviousStage() throws Exception {
        // 600 objects: the queue holds 256, so the producer must wait for the consumer to catch up.
        run("numbers 600 | slow -d 1 | count");
        StageStat producer = last().stages().get(0);
        StageStat slow = last().stages().get(1);
        assertThat(producer.blocked()).isGreaterThan(Duration.ofMillis(100));
        assertThat(slow.active()).isGreaterThan(Duration.ofMillis(300));
        assertThat(slow.in()).isEqualTo(600);
        assertThat(last().stages()).extracting(StageStat::bottleneck).containsExactly(false, true, false);
    }

    @Test
    void activeTimeIsTheWallTimeWithoutTheWaits() throws Exception {
        run("numbers 20 -d 5 | slow | count");
        for (StageStat stage : last().stages()) {
            Duration expected = stage.wall().minus(stage.starved()).minus(stage.blocked());
            assertThat(stage.active()).isBetween(Duration.ZERO, stage.wall());
            assertThat(Math.abs(stage.active().minus(expected).toMillis())).isLessThanOrEqualTo(20);
        }
    }

    @Test
    void aSingleStageLineIsMeasuredToo() throws Exception {
        run("items -n 4");
        assertThat(last().stages()).hasSize(1);
        StageStat stage = last().stages().getFirst();
        assertThat(stage.out()).isEqualTo(4);
        assertThat(stage.bottleneck()).isFalse(); // one stage is not a bottleneck
    }

    @Test
    void theBlocksAreTimed() throws Exception {
        run("items -n 5 | filter { $_.size > 10 } | count");
        var blocks = last().blocks();
        assertThat(blocks).hasSize(1);
        var block = blocks.getFirst();
        assertThat(block.stage()).isEqualTo(2);
        assertThat(block.source()).isEqualTo("$_.size > 10");
        assertThat(block.calls()).isEqualTo(5);
        assertThat(block.total()).isGreaterThanOrEqualTo(block.max());
        assertThat(block.max()).isGreaterThanOrEqualTo(block.first().isZero() ? Duration.ZERO : Duration.ofNanos(1));
        assertThat(block.average()).isLessThanOrEqualTo(block.max());
    }

    @Test
    void phasesAndJvmAreReported() throws Exception {
        run("items -n 3 | count");
        PerfReport report = last();
        assertThat(report.phases()).extracting(p -> p.name()).containsExactly("parse", "resolve", "execute", "display");
        Duration sum = report.phases().stream().map(p -> p.time()).reduce(Duration.ZERO, Duration::plus);
        assertThat(Math.abs(sum.minus(report.total()).toMillis())).isLessThanOrEqualTo(5);
        assertThat(report.jvm().heapPeak()).isPositive();
        assertThat(report.text()).isEqualTo("items -n 3 | count");
        assertThat(report.cancelled()).isFalse();
    }

    @Test
    void displayTimeIsSeparatedFromTheLastStage() throws Exception {
        run("items -n 3");
        var display = last().phases().stream().filter(p -> p.name().equals("display")).findFirst().orElseThrow();
        assertThat(display.time()).isPositive();
    }

    @Test
    void nativeCommandsAreReported() throws Exception {
        assumeFalse(Platform.isWindows(), "Unix commands");
        run("printf \"a\\nb\\n\" | count");
        var report = last();
        assertThat(report.stages()).hasSize(2);
        assertThat(report.stages().getFirst().in()).isNull();
        assertThat(report.stages().getFirst().out()).isNull(); // a native stage is only timed
        assertThat(report.natives()).hasSize(1);
        assertThat(report.natives().getFirst().command()).startsWith("printf");
        assertThat(report.natives().getFirst().exitCode()).isEqualTo(0);

        run("sh -c \"exit 0\"");
        assertThat(last().stages()).hasSize(1);
        assertThat(last().natives()).hasSize(1);
        assertThat(last().stages().getFirst().wall()).isPositive();
    }

    @Test
    void theSummaryIsPrintedAfterTheOutput() throws Exception {
        String text = run("items -n 2 | count");
        assertThat(text).contains("── perf · ").contains("stage").contains("count").contains("display")
                .contains("JVM");
        assertThat(text.indexOf("── perf")).isGreaterThan(text.indexOf("2\n"));
    }

    @Test
    void theBottleneckIsMarkedInTheSummary() throws Exception {
        assertThat(run("numbers 20 -d 10 | count")).contains("← bottleneck");
    }

    @Test
    void reportsAreKeptMostRecentFirstAndNumbered() throws Exception {
        run("items -n 1");
        run("items -n 2");
        assertThat(session.perfReports()).extracting(PerfReport::line).containsExactly(2, 1);
        assertThat(session.variable("perf")).isSameAs(session.perfReports().getFirst());
    }

    @Test
    void onlyTheLastFiftyLinesAreKept() throws Exception {
        for (int i = 0; i < Session.MAX_PERF_REPORTS + 5; i++) {
            run("items -n 1");
        }
        assertThat(session.perfReports()).hasSize(Session.MAX_PERF_REPORTS);
        assertThat(session.perfReports().getFirst().line()).isEqualTo(Session.MAX_PERF_REPORTS + 5);
    }

    @Test
    void nothingIsMeasuredWhenTheModeIsOff() throws Exception {
        interpreter.perfMode(false);
        String text = run("items -n 2");
        assertThat(text).doesNotContain("perf ·");
        assertThat(session.perfReports()).isEmpty();
        assertThat(session.variable("perf")).isNull();
    }

    @Test
    void emptyBackgroundAndPerfLinesAreNotMeasured() throws Exception {
        run("");
        run("   ");
        assertThat(session.perfReports()).isEmpty();
        assumeFalse(Platform.isWindows(), "Unix commands");
        run("sleep 0 &");
        assertThat(session.perfReports()).isEmpty();
        run("items -n 1");
        run("perf");
        assertThat(session.perfReports()).hasSize(1); // a line starting with perf leaves the previous report as it is
    }

    @Test
    void subExpressionsAndAssignmentsCountAsOneLine() throws Exception {
        run("$n = items -n 3 | count");
        assertThat(last().stages()).hasSize(2);
        assertThat(session.variable("n")).isEqualTo(3L);
        assertThat(out.toString()).contains("── perf"); // the summary is printed, but not captured in the variable
        run("$x = (items -n 2 | count) + 1");
        assertThat(last().stages()).isNotEmpty();
        assertThat(session.perfReports()).hasSize(2);
    }

    @Test
    void aCancelledLineStillReports() throws Exception {
        Thread runner = Thread.currentThread();
        var timer = new java.util.Timer(true);
        timer.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                runner.interrupt();
            }
        }, 150);
        try {
            interpreter.execute("infinite | count");
        } catch (InterruptedException | java.util.concurrent.CancellationException expected) {
            // cancelled, as with Ctrl+C
        } finally {
            timer.cancel();
            Thread.interrupted();
        }
        assertThat(session.perfReports()).hasSize(1);
        assertThat(last().cancelled()).isTrue();
        assertThat(last().stages()).hasSize(2);
        assertThat(out.toString()).contains("cancelled");
    }

    @Test
    void aLineThatFailsToParseIsNotMeasured() throws Exception {
        try {
            interpreter.execute("items | | count");
        } catch (RuntimeException expected) {
            // syntax error: nothing ran
        }
        assertThat(session.perfReports()).isEmpty();
    }

    @Test
    void theFooterGivesCountsInACompactForm() {
        assertThat(PerfFooter.count(null)).isEqualTo("-");
        assertThat(PerfFooter.count(999L)).isEqualTo("999");
        assertThat(PerfFooter.count(412_000L)).isEqualTo("412k");
        assertThat(PerfFooter.count(1_250_000L)).isEqualTo("1.3M");
    }

    @Test
    void shortDurationsAreReadable() {
        assertThat(Values.text(Duration.ofNanos(412))).isEqualTo("412 ns");
        assertThat(Values.text(Duration.ofNanos(1_400))).isEqualTo("1.4 µs");
        assertThat(Values.text(Duration.ofMillis(12))).isEqualTo("0.012 s");
    }
}
