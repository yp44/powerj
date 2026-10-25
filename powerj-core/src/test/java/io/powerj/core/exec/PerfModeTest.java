package io.powerj.core.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.powerj.core.lang.Ast;
import io.powerj.core.lang.Parser;

/** What surrounds the measure of a line in perf mode (FR-76): when it is on, what it keeps, when it ends. */
class PerfModeTest {

    @TempDir
    Path tmp;

    private final StringWriter out = new StringWriter();
    private final List<String> errors = new ArrayList<>();
    private Session session;
    private PerfMode mode;

    @BeforeEach
    void setUp() {
        session = new Session(tmp, tmp, new java.util.HashMap<>(Map.of()));
        mode = new PerfMode(session, new ShellIo(new PrintWriter(out, true), errors::add, false));
        mode.profiler(false); // the counters are enough here; PerfTest covers the profiler
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
        mode.profiler(true);
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
}
