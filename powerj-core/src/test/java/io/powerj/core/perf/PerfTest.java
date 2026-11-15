package io.powerj.core.perf;

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
import io.powerj.api.perf.PerfReport;
import io.powerj.api.perf.StageStat;
import io.powerj.core.command.CmdletRegistry;
import io.powerj.core.format.Values;
import io.powerj.core.runtime.Interpreter;
import io.powerj.core.runtime.Session;
import io.powerj.core.runtime.ShellIo;
import io.powerj.core.runtime.ShellStatistics;
import io.powerj.core.support.FakeCmdlets;
import io.powerj.fs.Host;

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

    /** Works for each object: burns the CPU for {@code -c} ms, allocates {@code -a} arrays of 10 KB, collects with {@code -g}. */
    @CmdletInfo(name = "work", category = "Test", summary = "Works", examples = "numbers 2 | work -c 100", passThrough = true)
    static final class Work implements Cmdlet<Work.Params, Object, Object> {

        record Params(@Option(shortName = 'c') Integer cpu, @Option(shortName = 'a') Integer alloc,
                      @Option(shortName = 'g') boolean gc) { }

        static volatile Object kept;

        static long spin(long millis) {
            long end = System.nanoTime() + millis * 1_000_000;
            long x = 0;
            while (System.nanoTime() < end) {
                for (int i = 0; i < 100_000; i++) {
                    x += i ^ (x >>> 3);
                }
            }
            return x;
        }

        @Override
        public void process(Params params, Object input, CmdletContext<Object> context) {
            if (params.cpu() != null) {
                kept = spin(params.cpu());
            }
            if (params.alloc() != null) {
                for (int i = 0; i < params.alloc(); i++) {
                    kept = new byte[10_000];
                }
            }
            context.emit(input);
        }

        @Override
        public void end(Params params, CmdletContext<Object> context) {
            if (params.gc()) {
                System.gc();
            }
        }
    }

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private Session session;
    private PerfMode perfMode;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        session = new Session(tmp, tmp, System.getenv());
        List<Cmdlet<?, ?, ?>> cmdlets = new ArrayList<>(FakeCmdlets.all());
        cmdlets.add(new Numbers());
        cmdlets.add(new Slow());
        cmdlets.add(new Work());
        var io = new ShellIo(new PrintWriter(out, true), errors::add, false);
        perfMode = new PerfMode(session, io, Profiler.discover());
        interpreter = new Interpreter(session, io, Map.of(), CmdletRegistry.of(cmdlets), perfMode);
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

    /**
     * What is asserted does not depend on how long anything takes: the counter has to wait for a producer that sleeps
     * before each object, and the producer, whose 20 objects fit in the queue, never waits for the counter. Which stage
     * is the bottleneck, and how the times add up, are checked with given times in {@code ExecutionObserverTest} (#142).
     */
    @Test
    void aSlowProducerStarvesTheNextStage() throws Exception {
        run("numbers 20 -d 10 | count");
        StageStat producer = last().stages().get(0);
        StageStat counter = last().stages().get(1);
        assertThat(counter.starved()).isPositive();
        assertThat(producer.starved()).isZero(); // nothing before it
        assertThat(producer.blocked()).isZero(); // the queue holds 256 objects
        assertThat(producer.active()).isEqualTo(producer.wall()); // so it never waited
    }

    @Test
    void aSlowConsumerBlocksThePreviousStage() throws Exception {
        // 600 objects: the queue holds 256, so the producer must wait for the consumer to catch up. It could avoid
        // the wait only if the consumer, which sleeps at least 1 ms for each object, had taken 344 of them before the
        // producer got to run: a stall of the runner of more than 344 ms. How long it waited is not asserted.
        run("numbers 600 | slow -d 1 | count");
        StageStat producer = last().stages().get(0);
        StageStat slow = last().stages().get(1);
        assertThat(producer.blocked()).isPositive();
        assertThat(slow.in()).isEqualTo(600);
        assertThat(slow.out()).isEqualTo(600);
    }

    @Test
    void activeTimeIsTheWallTimeWithoutTheWaits() throws Exception {
        run("numbers 20 -d 5 | slow | count");
        List<StageStat> stages = last().stages();
        for (StageStat stage : stages) {
            Duration expected = stage.wall().minus(stage.starved()).minus(stage.blocked());
            assertThat(stage.active()).isBetween(Duration.ZERO, stage.wall());
            if (stage != stages.getLast()) {
                assertThat(stage.active()).isEqualTo(expected);
            } else {
                assertThat(stage.active()).isLessThanOrEqualTo(expected); // the last stage also displays: not active time
            }
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
        // the phases are the whole line (execution is what the others leave): exact, whatever the times
        Duration sum = report.phases().stream().map(p -> p.time()).reduce(Duration.ZERO, Duration::plus);
        assertThat(sum).isEqualTo(report.total());
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
        assumeFalse(Host.windows(), "Unix commands");
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
        for (int i = 0; i < ShellStatistics.MAX_PERF_REPORTS + 5; i++) {
            run("items -n 1");
        }
        assertThat(session.perfReports()).hasSize(ShellStatistics.MAX_PERF_REPORTS);
        assertThat(session.perfReports().getFirst().line()).isEqualTo(ShellStatistics.MAX_PERF_REPORTS + 5);
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
        assumeFalse(Host.windows(), "Unix commands");
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

    // --- the profiler (part B) ---

    /**
     * With the real profiler, which samples as often as the timer of the system lets it (#142: a run on macOS found only 5): the stage that spins is found, the one that does not is not credited with the
     * spin. How many samples there are, and what share each method has, is not asserted: that is the profiler's.
     * Where the samples go into the report is checked with a fake profiler ({@code PerfModeTest}).
     */
    @Test
    void aBusyStageIsFoundHotInItsOwnThread() throws Exception {
        run("numbers 2 | work -c 150 | count");
        PerfReport report = last();
        var hot = report.hot().stream().filter(h -> h.stage() == 2).toList();
        assertThat(hot).isNotEmpty();
        assertThat(hot).anySatisfy(h -> assertThat(h.method()).endsWith("Work.spin"));
        assertThat(hot).allSatisfy(h -> assertThat(h.percent()).isBetween(0.0, 100.0));
        // count never runs Work.spin: whatever stray samples it has (the display of its result), none is there
        assertThat(report.hot()).filteredOn(h -> h.stage() == 3)
                .noneSatisfy(h -> assertThat(h.method()).endsWith("Work.spin"));
    }

    @Test
    void theLastStageRunsInTheCallingThreadAndIsFoundByTime() throws Exception {
        run("numbers 2 | work -c 150");
        var hot = last().hot().stream().filter(h -> h.stage() == 2).toList();
        assertThat(hot).isNotEmpty();
        assertThat(hot).anySatisfy(h -> assertThat(h.method()).endsWith("Work.spin"));
    }

    @Test
    void theFooterNamesTheHotMethodsOfTheBottleneck() throws Exception {
        String text = run("numbers 2 | work -c 150 | count");
        assertThat(text).contains("hot (work -c 150):").contains("Work.spin");
    }

    @Test
    void allocationsAreCountedPerStageAndType() throws Exception {
        run("numbers 3 | work -a 20000 | count");
        var alloc = last().alloc().stream().filter(a -> a.stage() == 2).toList();
        assertThat(alloc).isNotEmpty();
        assertThat(alloc.getFirst().type()).isEqualTo("byte[]");
        assertThat(alloc.getFirst().bytes()).isPositive();
    }

    @Test
    void garbageCollectionsAreListed() throws Exception {
        run("numbers 1 | work -g | count");
        assertThat(last().gc()).isNotEmpty();
        assertThat(last().gc().getFirst().cause()).contains("System.gc()");
        assertThat(last().gc().getFirst().pause()).isPositive();
    }

    @Test
    void theRecordingOfTheLineIsKeptAsAFlightRecorderFile() throws Exception {
        run("numbers 2 | count");
        Path file = last().recording();
        assertThat(file).isNotNull().isRegularFile();
        try (var events = new jdk.jfr.consumer.RecordingFile(file)) {
            assertThat(events.hasMoreEvents() || java.nio.file.Files.size(file) > 0).isTrue();
        }
    }

    @Test
    void withoutTheProfilerTheLineIsMeasuredWithTheCountersOnly() throws Exception {
        perfMode.profiling(false);
        String text = run("numbers 2 | work -c 100 | count");
        assertThat(last().recording()).isNull();
        assertThat(last().hot()).isEmpty();
        assertThat(last().gc()).isEmpty();
        assertThat(last().alloc()).isEmpty();
        assertThat(text).doesNotContain("hot (");
        assertThat(last().stages()).hasSize(3);
    }

    @Test
    void theProfilerIsThereInThisRuntime() {
        assertThat(PerfJfr.available()).isTrue();
    }

    @Test
    void arrayTypesAreReadable() {
        assertThat(JfrCapture.typeName("[B")).isEqualTo("byte[]");
        assertThat(JfrCapture.typeName("[[I")).isEqualTo("int[][]");
        assertThat(JfrCapture.typeName("[Ljava.lang.Object;")).isEqualTo("java.lang.Object[]");
        assertThat(JfrCapture.typeName("java.lang.String")).isEqualTo("java.lang.String");
        assertThat(PerfFooter.shortMethod("java.util.ArrayList.add")).isEqualTo("ArrayList.add");
        assertThat(PerfFooter.shortMethod("Main.run")).isEqualTo("Main.run");
    }

    @Test
    void aRecordingIsDeletedWhenItsReportIsNoLongerKept() throws Exception {
        var first = java.nio.file.Files.createTempFile(tmp, "line-", ".jfr");
        var jvm = new io.powerj.api.perf.JvmStat(1, 0, Duration.ZERO, 0, 0, Duration.ZERO);
        for (int i = 1; i <= ShellStatistics.MAX_PERF_REPORTS + 1; i++) {
            session.addPerfReport(new PerfReport(i, "l", Duration.ZERO, false, List.of(), List.of(), List.of(), List.of(),
                    jvm, List.of(), List.of(), List.of(), i == 1 ? first : null));
        }
        assertThat(first).doesNotExist();
        assertThat(session.perfReports()).hasSize(ShellStatistics.MAX_PERF_REPORTS);
    }

    // --- sampling period: POWERJ_PERF_JFR_SAMPLEPERIOD ---

    private long hotSamples() {
        return last().hot().stream().filter(h -> h.stage() == 2).mapToLong(h -> h.samples()).sum();
    }

    /**
     * A valid period set in the session environment is accepted without a message and the profiler still samples. That
     * the period asked of the profiler is the one set is checked with a fake profiler ({@code PerfModeTest}), and not by
     * comparing the samples of two runs: the profiler's timer gives one sample every 11 ms on a macOS runner whatever
     * the period under that, so "1 ms gives twice the samples of 10 ms" is false there (#101, #142).
     */
    @Test
    void theSamplingPeriodIsReadFromTheSessionEnvironment() throws Exception {
        session.environment().put("POWERJ_PERF_JFR_SAMPLEPERIOD", "1ms"); // as `env --set` in profile.pj does
        run("numbers 2 | work -c 150 | count");
        assertThat(hotSamples()).isPositive();
        assertThat(errors).isEmpty();
    }

    @Test
    void periodsAreReadWithAnOptionalUnit() {
        assertThat(PerfJfr.parseSamplePeriod("2ms")).isEqualTo(Duration.ofMillis(2));
        assertThat(PerfJfr.parseSamplePeriod("5")).isEqualTo(Duration.ofMillis(5));
        assertThat(PerfJfr.parseSamplePeriod(" 3 ms ")).isEqualTo(Duration.ofMillis(3));
        assertThat(PerfJfr.parseSamplePeriod("500us")).isEqualTo(Duration.ofNanos(500_000));
        assertThat(PerfJfr.parseSamplePeriod("0.5s")).isEqualTo(Duration.ofMillis(500));
        assertThat(PerfJfr.parseSamplePeriod("1.5MS")).isEqualTo(Duration.ofNanos(1_500_000));
        for (String bad : List.of("", "abc", "ms", "0", "0ms", "-1ms", "1.5.2ms", "2 minutes")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> PerfJfr.parseSamplePeriod(bad))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void anInvalidPeriodIsReportedOnceAndTheDefaultIsUsed() throws Exception {
        session.environment().put("POWERJ_PERF_JFR_SAMPLEPERIOD", "often");
        run("numbers 2 | work -c 150 | count");
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("POWERJ_PERF_JFR_SAMPLEPERIOD=often").contains("10 ms");
        assertThat(hotSamples()).isPositive(); // the profiler ran, with the default period
        run("numbers 2 | count");
        assertThat(errors).isEmpty(); // not repeated for the same value
        session.environment().put("POWERJ_PERF_JFR_SAMPLEPERIOD", "sometimes");
        run("numbers 2 | count");
        assertThat(errors).hasSize(1); // another value: reported again
    }

    /**
     * With the real profiler: the user is told that the period was raised, and the profiler still samples. That the
     * period asked of the profiler is 1 ms is checked where it is decided, with a fake profiler
     * ({@code PerfModeTest.aPeriodUnderTheMinimumBecomesTheMinimum}). How many samples arrive is not asserted: it
     * depends on the timer of the system. On the macOS runners the profiler gave one sample every 11 ms (22 to 32 in
     * 300 ms, 60 at best) and a test that wanted more than 40 failed half of the time (#101).
     */
    @Test
    void aPeriodUnderTheProfilersMinimumBecomesOneMillisecond() throws Exception {
        session.environment().put("POWERJ_PERF_JFR_SAMPLEPERIOD", "100us");
        run("numbers 2 | work -c 150 | count");
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("POWERJ_PERF_JFR_SAMPLEPERIOD=100us").contains("1 ms");
        assertThat(hotSamples()).isPositive(); // the profiler ran with the raised period
    }

    @Test
    void anEmptyPeriodMeansTheDefault() throws Exception {
        session.environment().put("POWERJ_PERF_JFR_SAMPLEPERIOD", "  ");
        run("numbers 2 | work -c 100 | count");
        assertThat(errors).isEmpty();
    }

    // --- allocation limit: POWERJ_PERF_JFR_ALLOCTHROTTLE ---

    private long allocationEvents() throws Exception {
        long count = 0;
        try (var events = new jdk.jfr.consumer.RecordingFile(last().recording())) {
            while (events.hasMoreEvents()) {
                if (events.readEvent().getEventType().getName().equals("jdk.ObjectAllocationSample")) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    void theAllocationLimitIsReadFromTheSessionEnvironment() throws Exception {
        session.environment().put("POWERJ_PERF_JFR_ALLOCTHROTTLE", "1/s");
        run("numbers 2 | work -a 60000 | count");
        long limited = allocationEvents();
        session.environment().put("POWERJ_PERF_JFR_ALLOCTHROTTLE", "off");
        run("numbers 2 | work -a 60000 | count");
        long unlimited = allocationEvents();
        // The only check that the profiler reads the rate the way it is written ("1/s": a rate it does not understand
        // means "no limit"), so the bound is kept, but weak: the run limited to 1/s has about one event per second it
        // lasts (a loaded runner makes it last longer), the other one hundreds, whatever the speed of the machine.
        assertThat(unlimited).isGreaterThan(limited);
        assertThat(errors).isEmpty();
    }

    @Test
    void ratesAreCheckedAndWrittenTheWayTheProfilerReadsThem() {
        assertThat(PerfJfr.parseAllocThrottle("100/s")).isEqualTo("100/s");
        assertThat(PerfJfr.parseAllocThrottle(" 5 / MS ")).isEqualTo("5/ms");
        assertThat(PerfJfr.parseAllocThrottle("600/m")).isEqualTo("600/m");
        assertThat(PerfJfr.parseAllocThrottle("36000/h")).isEqualTo("36000/h");
        assertThat(PerfJfr.parseAllocThrottle("007/s")).isEqualTo("7/s");
        assertThat(PerfJfr.parseAllocThrottle("OFF")).isEqualTo("off");
        // the profiler reads these as "no limit" and warns on the console: refused here
        for (String bad : List.of("", "abc", "100", "/s", "0/s", "-1/s", "10/min", "1.5/s", "5/", "99999999999/s",
                "100/s/s", "unlimited")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> PerfJfr.parseAllocThrottle(bad))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void anInvalidAllocationLimitIsReportedOnceAndTheDefaultIsUsed() throws Exception {
        session.environment().put("POWERJ_PERF_JFR_ALLOCTHROTTLE", "lots");
        run("numbers 2 | work -a 1000 | count");
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst()).contains("POWERJ_PERF_JFR_ALLOCTHROTTLE=lots").contains("100/s");
        assertThat(last().recording()).isNotNull();
        run("numbers 2 | count");
        assertThat(errors).isEmpty();
    }

    @Test
    void thePeriodAndTheAllocationLimitAreIndependent() throws Exception {
        session.environment().put("POWERJ_PERF_JFR_SAMPLEPERIOD", "bad");
        session.environment().put("POWERJ_PERF_JFR_ALLOCTHROTTLE", "50/s");
        run("numbers 2 | count");
        assertThat(errors).hasSize(1).first().asString().contains("POWERJ_PERF_JFR_SAMPLEPERIOD");
    }

    // --- POWERJ_PERF_AUTOEXIT ---

    @Test
    void theModeEndsAfterTheNextMeasuredLineWhenAutoExitIsOne() throws Exception {
        session.environment().put("POWERJ_PERF_AUTOEXIT", "1");
        String text = run("items -n 2");
        assertThat(text).contains("── perf ·"); // the summary of that line is printed
        assertThat(interpreter.perfMode()).isFalse();
        assertThat(session.perfReports()).hasSize(1);
        assertThat(run("items -n 2")).doesNotContain("── perf ·");
        assertThat(session.perfReports()).hasSize(1);
        interpreter.perfMode(true); // Alt+P again: measures one more line
        run("items -n 2");
        assertThat(session.perfReports()).hasSize(2);
        assertThat(interpreter.perfMode()).isFalse();
    }

    @Test
    void theModeStaysOnForAnyOtherValue() throws Exception {
        for (String value : List.of("0", "", "yes", "11", "true", "1.0")) {
            session.environment().put("POWERJ_PERF_AUTOEXIT", value);
            run("items -n 1");
            assertThat(interpreter.perfMode()).as("value '%s'", value).isTrue();
        }
        session.environment().remove("POWERJ_PERF_AUTOEXIT");
        run("items -n 1");
        assertThat(interpreter.perfMode()).isTrue();
    }

    @Test
    void spacesAroundTheOneAreIgnored() throws Exception {
        session.environment().put("POWERJ_PERF_AUTOEXIT", " 1 ");
        run("items -n 1");
        assertThat(interpreter.perfMode()).isFalse();
    }

    @Test
    void linesThatAreNotMeasuredDoNotUseUpTheMode() throws Exception {
        session.environment().put("POWERJ_PERF_AUTOEXIT", "1");
        run("");
        run("perf");
        assertThat(interpreter.perfMode()).isTrue();
        assertThat(session.perfReports()).isEmpty();
    }

    @Test
    void aCancelledLineAlsoEndsTheMode() throws Exception {
        session.environment().put("POWERJ_PERF_AUTOEXIT", "1");
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
        assertThat(last().cancelled()).isTrue();
        assertThat(interpreter.perfMode()).isFalse();
    }
}
