package io.powerj.core.perf;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.lang.Ast;
import io.powerj.core.lang.Parser;
import io.powerj.core.runtime.ExecutionObserver;
import io.powerj.core.runtime.Session;
import io.powerj.core.runtime.ShellIo;

/** What surrounds the measure of a line in perf mode (FR-76): when it is on, what it keeps, when it ends. */
class PerfModeTest {

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = new ArrayList<>();
    private Session session;
    private PerfMode mode;
    private final FakeProfiler fake = new FakeProfiler();

    /** A profiler that writes nothing and says what it was asked. */
    private static final class FakeProfiler implements Profiler {
        final List<String> calls = new ArrayList<>();
        final List<Duration> periods = new ArrayList<>();
        final List<String> throttles = new ArrayList<>();
        final List<Path> files = new ArrayList<>();
        boolean canStart = true;
        JfrData data = JfrData.EMPTY;

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public void warmUp(Path file) {
            calls.add("warmUp");
            files.add(file);
        }

        @Override
        public Recording start(Path file, Duration samplePeriod, String allocThrottle) {
            calls.add("start");
            files.add(file);
            periods.add(samplePeriod);
            throttles.add(allocThrottle);
            if (!canStart) {
                return null;
            }
            return new Recording() {
                @Override
                public Path file() {
                    return file;
                }

                @Override
                public JfrData finish(int line, List<StageProbe> stages) {
                    calls.add("finish " + line);
                    return data;
                }
            };
        }
    }

    private PerfMode withFakeProfiler() {
        mode = new PerfMode(session, new ShellIo(new PrintWriter(out, true), errors::add, false),
                Optional.of(fake));
        return mode;
    }

    @BeforeEach
    void setUp() {
        session = new Session(tmp, tmp, new java.util.HashMap<>(Map.of()));
        mode = new PerfMode(session, new ShellIo(new PrintWriter(out, true), errors::add, false), Profiler.discover());
        mode.profiling(false); // the counters are enough here; PerfTest covers the profiler
    }

    private static Ast.Script script(String line) {
        return Parser.parse(line, _ -> false);
    }

    private PerfMode.Measurement start(String line) {
        return mode.start(line, script(line), System.nanoTime(), 0);
    }

    @Test
    void nothingIsMeasuredWhileTheModeIsOff() {
        var measurement = start("items");

        assertThat(measurement).isSameAs(PerfMode.Measurement.NONE);
        assertThat(measurement.observer()).isSameAs(ExecutionObserver.NONE);
        measurement.finish(false);
        assertThat(session.perfReports()).isEmpty();
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void aMeasuredLineKeepsItsReportAndPrintsItsSummary() {
        mode.on(true);

        var measurement = start("items");
        assertThat(measurement.observer()).isInstanceOf(PerfRecorder.class);
        measurement.finish(false);

        assertThat(session.perfReports()).singleElement().satisfies(report -> {
            assertThat(report.text()).isEqualTo("items");
            assertThat(report.line()).isEqualTo(1);
            assertThat(report.cancelled()).isFalse();
        });
        assertThat(out.toString()).isNotEmpty();
        assertThat(mode.on()).isTrue();
    }

    @Test
    void aCancelledLineIsReportedAsCancelled() {
        mode.on(true);

        start("items").finish(true);

        assertThat(session.perfReports()).singleElement().satisfies(report -> assertThat(report.cancelled()).isTrue());
    }

    @Test
    void theLinesAreNumberedInTheOrderTheyWereMeasured() {
        mode.on(true);

        start("items").finish(false);
        start("items | count").finish(false);

        assertThat(session.perfReports()).extracting(report -> report.line()).containsExactly(2, 1);
    }

    @Test
    void anEmptyLineABackgroundLineAndThePerfCommandAreNotMeasured() {
        mode.on(true);

        assertThat(start("")).isSameAs(PerfMode.Measurement.NONE);
        assertThat(start("sleep 1 &")).isSameAs(PerfMode.Measurement.NONE);
        assertThat(start("perf")).isSameAs(PerfMode.Measurement.NONE);
        assertThat(start("perf --last")).isSameAs(PerfMode.Measurement.NONE);
        assertThat(start("items | perf")).isNotSameAs(PerfMode.Measurement.NONE); // only the first command counts
        assertThat(start("^perf")).isNotSameAs(PerfMode.Measurement.NONE); // a program called perf is measured
    }

    @Test
    void theAutoExitVariableEndsTheModeAfterTheNextMeasuredLine() {
        session.environment().put(PerfMode.AUTOEXIT_VARIABLE, " 1 ");
        mode.on(true);

        start("items").finish(false);

        assertThat(mode.on()).isFalse();
        assertThat(start("items")).isSameAs(PerfMode.Measurement.NONE);
    }

    @Test
    void theModeStaysOnWithoutTheAutoExitVariable() {
        session.environment().put(PerfMode.AUTOEXIT_VARIABLE, "0");
        mode.on(true);

        start("items").finish(false);

        assertThat(mode.on()).isTrue();
    }

    @Test
    void theSummaryIsPrintedEvenIfTheThreadWasInterrupted() {
        mode.on(true);
        var measurement = start("items");

        Thread.currentThread().interrupt();
        try {
            measurement.finish(true);

            assertThat(out.toString()).isNotEmpty();
            assertThat(Thread.currentThread().isInterrupted()).as("the flag is given back").isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void recordingsAreCreatedInOneFolderThatDeleteFolderRemoves() throws IOException {
        mode.profiling(true);
        org.junit.jupiter.api.Assumptions.assumeTrue(PerfJfr.available());
        mode.on(true); // warms the profiler up: that creates the folder

        Path folder = mode.recordings();
        assertThat(folder).isDirectory();
        Files.createDirectory(folder.resolve("nested"));
        Files.writeString(folder.resolve("nested/line-1.jfr"), "x");
        Files.writeString(folder.resolve("line-2.jfr"), "y");

        PerfMode.deleteFolder(folder);

        assertThat(folder).doesNotExist();
    }

    @Test
    void deletingAFolderThatIsAlreadyGoneIsNotAnError() {
        PerfMode.deleteFolder(tmp.resolve("gone"));
    }

    // --- the profiler (a fake one: PerfTest covers the real one) ---

    @Test
    void theProfilerIsWarmedUpWhenTheModeIsSwitchedOn() {
        withFakeProfiler().on(true);

        assertThat(fake.calls).containsExactly("warmUp");
        assertThat(fake.files.getFirst().getParent()).isEqualTo(mode.recordings());
    }

    @Test
    void aMeasuredLineIsRecordedAndItsDataGoesIntoTheReport() {
        fake.data = new JfrData(List.of(new io.powerj.api.HotMethod(1, 1, "a.B.run", 7, 70.0)), List.of(), List.of());
        withFakeProfiler().on(true);
        fake.calls.clear();

        start("items").finish(false);

        assertThat(fake.calls).containsExactly("start", "finish 1");
        assertThat(session.perfReports()).singleElement().satisfies(report -> {
            assertThat(report.hot()).extracting(hot -> hot.method()).containsExactly("a.B.run");
            assertThat(report.recording()).isEqualTo(fake.files.getLast());
        });
    }

    @Test
    void theSamplingPeriodAndTheAllocationLimitComeFromTheSessionEnvironment() {
        session.environment().put(PerfJfr.SAMPLE_PERIOD_VARIABLE, "2ms");
        session.environment().put(PerfJfr.ALLOC_THROTTLE_VARIABLE, " 5 / MS ");
        withFakeProfiler().on(true);

        start("items");

        assertThat(fake.periods).containsExactly(Duration.ofMillis(2));
        assertThat(fake.throttles).containsExactly("5/ms");
    }

    @Test
    void withoutSettingsTheProfilersDefaultsApply() {
        withFakeProfiler().on(true);

        start("items");

        assertThat(fake.periods).containsExactly(PerfJfr.DEFAULT_SAMPLE_PERIOD);
        assertThat(fake.throttles).containsExactly((String) null);
    }

    @Test
    void aWrongSettingIsReportedOncePerValueAndTheDefaultIsUsed() {
        session.environment().put(PerfJfr.SAMPLE_PERIOD_VARIABLE, "often");
        withFakeProfiler().on(true);

        start("items");
        start("items");
        session.environment().put(PerfJfr.SAMPLE_PERIOD_VARIABLE, "sometimes");
        start("items");

        assertThat(errors).hasSize(2);
        assertThat(errors.getFirst()).contains("often");
        assertThat(errors.getLast()).contains("sometimes");
        assertThat(fake.periods).containsOnly(PerfJfr.DEFAULT_SAMPLE_PERIOD);
    }

    @Test
    void aPeriodUnderTheMinimumBecomesTheMinimum() {
        session.environment().put(PerfJfr.SAMPLE_PERIOD_VARIABLE, "100us");
        withFakeProfiler().on(true);

        start("items");

        assertThat(fake.periods).containsExactly(PerfJfr.MINIMUM_SAMPLE_PERIOD);
        assertThat(errors).hasSize(1);
    }

    @Test
    void aProfilerThatCannotStartMeansTheLineIsMeasuredWithoutIt() {
        fake.canStart = false;
        withFakeProfiler().on(true);
        fake.calls.clear();

        start("items").finish(false);

        assertThat(fake.calls).containsExactly("start");
        assertThat(session.perfReports()).singleElement().satisfies(report -> {
            assertThat(report.recording()).isNull();
            assertThat(report.hot()).isEmpty();
        });
    }

    @Test
    void withoutAProfilerNoRecordingFileIsCreated() {
        mode = new PerfMode(session, new ShellIo(new PrintWriter(out, true), errors::add, false), Optional.empty());
        mode.on(true);

        start("items").finish(false);

        assertThat(mode.recordings()).isNull();
        assertThat(session.perfReports()).singleElement().satisfies(report -> assertThat(report.recording()).isNull());
    }

    @Test
    void switchingTheProfilerOffMeansNoRecording() {
        withFakeProfiler().profiling(false);
        mode.on(true);

        start("items").finish(false);

        assertThat(fake.calls).isEmpty();
        assertThat(mode.recordings()).isNull();
    }

    @Test
    void theProfilerOfThisRuntimeIsFoundWithoutBeingNamed() {
        org.junit.jupiter.api.Assumptions.assumeTrue(PerfJfr.available(), "no jdk.jfr in this runtime");

        assertThat(Profiler.discover()).containsInstanceOf(JfrProfiler.class);
        assertThat(new JfrProfiler().available()).isTrue();
    }
}
